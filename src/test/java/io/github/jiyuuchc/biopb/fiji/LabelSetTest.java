package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import biopb.tensor.TensorDescriptor;
import ij.ImagePlus;
import ij.measure.Calibration;
import io.github.jiyuuchc.biopb.fiji.TreeModelBuilder.Kind;
import io.github.jiyuuchc.biopb.fiji.TreeModelBuilder.Node;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.Test;

/** Label sets: id rule, tree rows, alignment with the image, broadcast, calibration. No display needed. */
public class LabelSetTest {

    private static List<String> L(String... s) { return Arrays.asList(s); }

    // --- id rule (mirrors the napari widget's split_label_array_id) -------------

    @Test
    public void idRuleTakesTheLastSegmentWithANameAfterIt() {
        LabelId.Address a = LabelId.parse("src0/@labels/nuclei");
        assertEquals("src0", a.imageArrayId);
        assertEquals("nuclei", a.name);
        assertNull(a.level);

        a = LabelId.parse("src0/0/@labels/cells/1"); // anything past the name is a native level
        assertEquals("src0/0", a.imageArrayId);
        assertEquals("cells", a.name);
        assertEquals("1", a.level);

        a = LabelId.parse("s/@labels/x/@labels/y"); // the last one wins
        assertEquals("s/@labels/x", a.imageArrayId);
        assertEquals("y", a.name);

        assertNull(LabelId.parse("src0/@labels")); // no name after it
        assertNull(LabelId.parse("src0/@labels/")); // empty name
        assertNull(LabelId.parse("@labels/x")); // a bare source called @labels is a source
        assertNull(LabelId.parse("src0/0"));
        assertNull(LabelId.parse(null));
        assertEquals("s/@labels", LabelId.parse("s/@labels/@labels/n").imageArrayId);
    }

    // --- tree --------------------------------------------------------------------

    private static List<Node> kids(DefaultMutableTreeNode n) { return TreeModelBuilderTest.kids(n); }

    @Test
    public void singleImageKeepsItsShapeAndOpensAloneWithSetsUnderneath() {
        DefaultMutableTreeNode n = TreeModelBuilder.sourceNode(TreeModelBuilderTest.source("s", "u",
                TreeModelBuilderTest.tensor("s/0", "uint16", 64, 32),
                TreeModelBuilderTest.tensor("s/0/@labels/nuclei", "uint16", 64, 32),
                TreeModelBuilderTest.tensor("s/0/@labels/cells/1", "uint32", 32, 16),
                TreeModelBuilderTest.tensor("s/0/@labels/a", "uint8", 64, 32)), "img");
        Node p = TreeModelBuilder.nodeOf(n);
        assertEquals("64×32 uint16", p.getSuffix()); // not "4 tensors"
        assertEquals(Arrays.asList("s/0"), p.getArrayIds()); // double-click opens the image, not a set
        assertTrue(p.isOpenable());
        List<Node> k = kids(n);
        assertEquals(3, k.size());
        assertEquals(Kind.LABEL, k.get(0).getKind());
        // sorted by array id; the row reads the set's name, not a pyramid level
        assertEquals(TreeModelBuilder.LABEL_GLYPH + " a", k.get(0).getText());
        assertEquals(TreeModelBuilder.LABEL_GLYPH + " cells", k.get(1).getText());
        assertEquals(Arrays.asList("s/0/@labels/cells/1"), k.get(1).getArrayIds());
        assertTrue(k.get(1).isOpenable());
        assertEquals("32×16 uint32", k.get(1).getSuffix());
    }

    @Test
    public void setsNestUnderTheirOwnImageAndOpenAllSkipsThem() {
        DefaultMutableTreeNode n = TreeModelBuilder.sourceNode(TreeModelBuilderTest.source("s", "u",
                TreeModelBuilderTest.tensor("s/a", "uint8", 8),
                TreeModelBuilderTest.tensor("s/b", "uint8", 8),
                TreeModelBuilderTest.tensor("s/b/@labels/m", "uint8", 8)), "img");
        Node p = TreeModelBuilder.nodeOf(n);
        assertEquals("2 tensors", p.getSuffix());
        assertEquals(Arrays.asList("s/a", "s/b"), p.getArrayIds());
        DefaultMutableTreeNode b = (DefaultMutableTreeNode) n.getChildAt(1);
        assertEquals(0, n.getChildAt(0).getChildCount());
        assertEquals(Kind.LABEL, kids(b).get(0).getKind());
    }

    @Test
    public void aSetWhoseImageIsNotListedKeepsARowOfItsOwn() {
        List<LabelId.Group> g = LabelId.group(Arrays.<Map<?, ?>>asList(
                TreeModelBuilderTest.tensor("s/x", "uint8", 8), TreeModelBuilderTest.tensor("s/gone/@labels/m", "uint8", 8)));
        assertEquals(2, g.size());
        assertEquals("s/gone/@labels/m", g.get(1).image.get("array_id"));
        assertTrue(g.get(0).labelSets.isEmpty());
    }

    // --- alignment -------------------------------------------------------------------

    private static final List<String> TCZYX = L("t", "c", "z", "y", "x");

    @Test
    public void setWithASingletonChannelBindsAndBroadcasts() {
        LabelAlignment a = LabelAlignment.decide(TCZYX, new long[] {3, 2, 4, 96, 128}, TCZYX,
                new long[] {3, 1, 4, 96, 128}, null);
        assertTrue(a.note, a.aligned);
        assertEquals(2, a.sizeC);
        assertEquals(4, a.sizeZ);
        assertEquals(3, a.sizeT);
        assertEquals("tczyx", a.labelMap.roles());
    }

    @Test
    public void setOmitsTheImagesSamplesAxis() {
        LabelAlignment a = LabelAlignment.decide(L("y", "x", "s"), new long[] {5, 6, 3}, L("y", "x"),
                new long[] {5, 6}, null);
        assertTrue(a.note, a.aligned);
        assertEquals("yx", a.labelMap.roles());
        assertEquals(1, a.imageMap.x);
        // same rank as the image but the image has a samples axis: not bound
        assertFalse(LabelAlignment.decide(L("y", "x", "s"), new long[] {5, 6, 3}, L("y", "x", "s"),
                new long[] {5, 6, 3}, null).aligned);
    }

    @Test
    public void rankAndExtentMismatchesOpenAtTheSetsOwnAxes() {
        LabelAlignment a = LabelAlignment.decide(TCZYX, new long[] {1, 1, 4, 96, 128}, L("z", "y", "x"),
                new long[] {4, 96, 128}, null);
        assertFalse(a.aligned);
        assertTrue(a.note, a.note.startsWith("Label set is not bound to its image's axes"));
        assertTrue(a.note, a.note.contains("rank 3 where its image has 5"));
        assertEquals("zyx", a.labelMap.roles());
        assertEquals(4, a.sizeZ);

        a = LabelAlignment.decide(TCZYX, new long[] {1, 1, 4, 96, 128}, TCZYX, new long[] {1, 1, 4, 96, 64}, null);
        assertFalse(a.aligned);
        assertTrue(a.note, a.note.contains("extent"));

        a = LabelAlignment.decide(TCZYX, new long[] {1, 1, 4, 96, 128}, TCZYX, new long[] {1, 1, 2, 96, 128}, null);
        assertFalse(a.aligned);
        assertTrue(a.note, a.note.contains("z length 2"));
    }

    @Test
    public void anUndescribableImageIsNotAFailure() {
        LabelAlignment a = LabelAlignment.decide(null, null, L("y", "x"), new long[] {5, 6}, null);
        assertFalse(a.aligned);
        assertTrue(a.note, a.note.contains("its image could not be described"));
    }

    @Test
    public void anUnalignedSetWithNoUsableAxesStillAsksForAnOverride() {
        try {
            LabelAlignment.decide(null, null, L("dim0", "dim1", "dim2"), new long[] {2, 5, 6}, null);
            fail("expected AxisMappingException");
        } catch (AxisMappingException e) {
            assertEquals(AxisMappingException.Reason.NEEDS_OVERRIDE, e.getReason());
        }
        assertEquals("zyx", LabelAlignment.decide(null, null, L("dim0", "dim1", "dim2"), new long[] {2, 5, 6}, "zyx")
                .labelMap.roles());
    }

    // --- broadcast ----------------------------------------------------------------------

    @Test
    public void broadcastRepeatsSingletonAxesAndReadsOnce() {
        FakePlaneSource inner = new FakePlaneSource(1, 2, 1); // c=1, z=2, t=1
        BroadcastPlaneSource b = new BroadcastPlaneSource(inner, 3, 2, 2);
        assertEquals(3, b.getSizeC());
        assertEquals(2, b.getSizeT());
        short[] p00 = (short[]) b.readPlane(0, 0, 1);
        short[] p21 = (short[]) b.readPlane(1, 2, 1);
        assertArrayEquals(p00, p21);
        assertEquals(10, p00[0]); // z=1: 0*1000 + 0*100 + 1*10 + x%10
        assertEquals(1, inner.reads.get()); // same inner plane
        p00[0] = 99; // the caller owns what it gets
        assertEquals(10, ((short[]) b.readPlane(0, 1, 1))[0]);
        b.readPlane(0, 0, 0);
        assertEquals(2, inner.reads.get());
        try {
            new BroadcastPlaneSource(inner, 1, 3, 1);
            fail("z of 2 cannot become 3");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("z"));
        }
    }

    @Test
    public void griddedWrapsOnlyWhenTheGridDiffers() {
        LabelAlignment a = LabelAlignment.decide(TCZYX, new long[] {1, 2, 1, 4, 6}, TCZYX, new long[] {1, 1, 1, 4, 6}, null);
        LabelOpener.Bound bound = new LabelOpener.Bound(a, null, "m");
        FakePlaneSource one = new FakePlaneSource(1, 1, 1);
        PlaneSource g = bound.gridded(one);
        assertEquals(2, g.getSizeC());
        ImagePlus imp = TensorOpener.assemble(g, "m");
        assertEquals(2, imp.getNChannels());
        FakePlaneSource two = new FakePlaneSource(2, 1, 1);
        assertSame(two, bound.gridded(two));
    }

    // --- calibration -----------------------------------------------------------------------

    private static TensorDescriptor desc(List<String> labels, long[] shape, double[] scale, String unit) {
        TensorDescriptor.Builder b = TensorDescriptor.newBuilder().setArrayId("x").setDtype("<u2");
        for (String l : labels) b.addDimLabels(l);
        for (long n : shape) b.addShape(n);
        for (double s : scale) { b.addPhysicalScale(s); b.addPhysicalUnit(unit); }
        return b.build();
    }

    @Test
    public void aBoundSetTakesTheImagesCalibrationAtItsOwnLevel() {
        // image: y, x, s (RGB); set: y, x. Level scale hint 2 on both.
        TensorDescriptor image = desc(L("y", "x", "s"), new long[] {8, 8, 3}, new double[] {0.5, 0.25, 1}, "nm");
        LabelAlignment a = LabelAlignment.decide(L("y", "x", "s"), new long[] {8, 8, 3}, L("y", "x"),
                new long[] {8, 8}, null);
        assertTrue(a.aligned);
        Calibration cal = new Calibration();
        new LabelOpener.Bound(a, image, "m").calibrate(cal, desc(L("y", "x"), new long[] {8, 8},
                new double[] {9, 9}, "mm"), new long[] {2, 2});
        assertEquals(1.0, cal.pixelHeight, 0); // 0.5 nm * 2, from the image, not the set's 9 mm
        assertEquals(0.5, cal.pixelWidth, 0);
        assertEquals("nm", cal.getXUnit());
    }

    @Test
    public void anUnboundSetUsesItsOwnCalibration() {
        TensorDescriptor own = desc(L("y", "x"), new long[] {8, 8}, new double[] {3, 4}, "mm");
        LabelAlignment a = LabelAlignment.decide(null, null, L("y", "x"), new long[] {8, 8}, null);
        Calibration cal = new Calibration();
        new LabelOpener.Bound(a, null, "m").calibrate(cal, own, new long[] {1, 1});
        assertEquals(3, cal.pixelHeight, 0);
        assertEquals(4, cal.pixelWidth, 0);
    }

    @Test
    public void idsAre8Or16BitWhenTheyFitElseFloat() {
        assertEquals(PlaneSource.Kind.GRAY8, SdkPlaneSource.kindOf("|u1", false));
        assertEquals(PlaneSource.Kind.GRAY16, SdkPlaneSource.kindOf("<u2", false));
        assertEquals(PlaneSource.Kind.GRAY32, SdkPlaneSource.kindOf("<u4", false));
        assertEquals(PlaneSource.Kind.GRAY32, SdkPlaneSource.kindOf("<i8", false));
    }
}
