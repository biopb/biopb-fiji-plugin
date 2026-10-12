package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ij.measure.Calibration;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AxisAndSourceTest {

    private static List<String> L(String... s) { return Arrays.asList(s); }

    private static void rejects(List<String> labels, long[] shape, String override, String fragment) {
        try {
            AxisMap.resolve(labels, shape, override);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }

    @Test
    public void labeledAxes() {
        AxisMap m = AxisMap.resolve(L("t", "c", "z", "y", "x"), new long[] {3, 2, 4, 96, 128}, null);
        assertEquals(0, m.t); assertEquals(1, m.c); assertEquals(2, m.z); assertEquals(3, m.y); assertEquals(4, m.x);
        assertEquals(-1, m.s);
        m = AxisMap.resolve(L("Z", "Y", "X", "S"), new long[] {5, 8, 9, 3}, null); // case-insensitive, S last
        assertEquals(3, m.s); assertEquals(0, m.z); assertEquals(-1, m.t);
    }

    @Test
    public void nonCanonicalOrderAndSingletonUnknownAxis() {
        AxisMap m = AxisMap.resolve(L("c", "x", "y", "foo"), new long[] {2, 8, 9, 1}, null);
        assertEquals(1, m.x); assertEquals(2, m.y); assertEquals(0, m.c);
    }

    @Test
    public void unlabeledFallsBackToYx() {
        AxisMap m = AxisMap.resolve(L("dim0", "dim1"), new long[] {5, 6}, null);
        assertEquals(0, m.y); assertEquals(1, m.x);
        m = AxisMap.resolve(Collections.<String>emptyList(), new long[] {1, 1, 5, 6}, null);
        assertEquals(2, m.y); assertEquals(3, m.x);
    }

    @Test
    public void unlabeledNeedsOverrideForMoreAxes() {
        rejects(L("dim0", "dim1", "dim2"), new long[] {4, 5, 6}, null, "override");
        AxisMap m = AxisMap.resolve(L("dim0", "dim1", "dim2"), new long[] {4, 5, 6}, "zyx");
        assertEquals(0, m.z); assertEquals(1, m.y); assertEquals(2, m.x);
    }

    @Test
    public void overrideBeatsLabels() {
        AxisMap m = AxisMap.resolve(L("t", "c", "y", "x"), new long[] {2, 3, 5, 6}, "zcyx");
        assertEquals(0, m.z); assertEquals(-1, m.t);
    }

    @Test
    public void rejections() {
        rejects(L("t", "q", "y", "x"), new long[] {2, 3, 5, 6}, null, "Unsupported axis");
        rejects(L("y", "x", "y"), new long[] {2, 3, 5}, null, "twice");
        rejects(L("z", "x"), new long[] {2, 3}, null, "y and x");
        rejects(L("dim0", "dim1", "dim2"), new long[] {4, 5, 6}, "zy", "characters");
        rejects(L("dim0", "dim1", "dim2"), new long[] {4, 5, 6}, "-yx", "ignored");
        rejects(L("y", "x", "s"), new long[] {4, 5, 4}, null, "Sample axis");
        rejects(L("x"), new long[] {4}, null, "at least");
        rejects(L("t", "c", "z", "y", "x", "w"), new long[] {2, 2, 2, 4, 4, 2}, null, "5 non-singleton");
    }

    @Test
    public void kindOfDtype() {
        assertEquals(PlaneSource.Kind.GRAY8, SdkPlaneSource.kindOf("|u1", false));
        assertEquals(PlaneSource.Kind.GRAY16, SdkPlaneSource.kindOf("<u2", false));
        assertEquals(PlaneSource.Kind.GRAY32, SdkPlaneSource.kindOf("<f4", false));
        for (String d : new String[] {"<i2", ">u4", "<i4", "<f8", "<i8", "|b1", "|i1"}) {
            assertEquals(d, PlaneSource.Kind.GRAY32, SdkPlaneSource.kindOf(d, false));
        }
        assertEquals(PlaneSource.Kind.RGB, SdkPlaneSource.kindOf("|u1", true));
        try { SdkPlaneSource.kindOf("<u2", true); fail(); } catch (IllegalArgumentException expected) { }
        try { SdkPlaneSource.kindOf("weird", false); fail(); } catch (IllegalArgumentException expected) { }
    }

    /** imglib2 dims [t,c,z,y,x]: value = f(t,c,z,y,x); read through SdkPlaneSource. */
    @Test
    public void sdkSourceReadsPlanesFromDescriptorOrder() {
        long[] dims = {2, 3, 4, 5, 7}; // imglib2 dim order == descriptor order
        short[] data = new short[2 * 3 * 4 * 5 * 7];
        ArrayImg<UnsignedShortType, ShortArray> img = ArrayImgs.unsignedShorts(data, dims);
        net.imglib2.RandomAccess<UnsignedShortType> ra = img.randomAccess();
        for (int t = 0; t < 2; t++) for (int c = 0; c < 3; c++) for (int z = 0; z < 4; z++)
            for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) {
                ra.setPosition(new long[] {t, c, z, y, x});
                ra.get().set(t * 1000 + c * 100 + z * 10 + y * 3 + x);
            }
        AxisMap m = AxisMap.resolve(L("t", "c", "z", "y", "x"), dims, null);
        SdkPlaneSource s = new SdkPlaneSource(img, m, "<u2");
        assertEquals(7, s.getWidth()); assertEquals(5, s.getHeight());
        assertEquals(3, s.getSizeC()); assertEquals(4, s.getSizeZ()); assertEquals(2, s.getSizeT());
        short[] p = (short[]) s.readPlane(1, 2, 3);
        for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) {
            assertEquals(1000 + 200 + 30 + y * 3 + x, p[y * 7 + x]);
        }
    }

    @Test
    public void sdkSourceHandlesXBeforeYAndMissingAxes() {
        long[] dims = {4, 3}; // (x, y)
        byte[] data = new byte[12];
        for (int i = 0; i < 12; i++) data[i] = (byte) i; // x fastest in imglib2: value = x + 4*y
        AxisMap m = AxisMap.resolve(L("x", "y"), dims, null);
        SdkPlaneSource s = new SdkPlaneSource(ArrayImgs.unsignedBytes(data, dims), m, "|u1");
        assertEquals(1, s.getSizeC());
        byte[] p = (byte[]) s.readPlane(0, 0, 0);
        assertArrayEquals(data, p);
    }

    @Test
    public void sdkSourceConvertsOtherDtypesToFloat() {
        long[] dims = {2, 2};
        AxisMap m = AxisMap.resolve(L("y", "x"), dims, null);
        PlaneSource s = new SdkPlaneSource(ArrayImgs.ints(new int[] {-1, 2, 70000, 4}, dims), m, "<i4");
        assertEquals(PlaneSource.Kind.GRAY32, s.getKind());
        float[] p = (float[]) s.readPlane(0, 0, 0);
        // dims (y,x) with y first: imglib2 y-fastest, so the (x,y) flat order is transposed
        assertEquals(-1f, p[0], 0); assertEquals(70000f, p[1], 0); assertEquals(2f, p[2], 0); assertEquals(4f, p[3], 0);
    }

    @Test
    public void sdkSourceRgb() {
        long[] dims = {2, 2, 3}; // y, x, s
        byte[] data = new byte[12];
        for (int s = 0; s < 3; s++) for (int i = 0; i < 4; i++) data[s * 4 + i] = (byte) (s == 0 ? 255 : s * 16 + i);
        AxisMap m = AxisMap.resolve(L("y", "x", "s"), dims, null);
        PlaneSource s = new SdkPlaneSource(ArrayImgs.unsignedBytes(data, dims), m, "|u1");
        assertEquals(PlaneSource.Kind.RGB, s.getKind());
        int[] p = (int[]) s.readPlane(0, 0, 0);
        assertEquals(0xff0000 | (16 << 8) | 32, p[0]); // y=0,x=0 -> flat index 0
        assertEquals(0xff0000 | (17 << 8) | 33, p[2]); // y=1,x=0 (data idx 1) lands at x-fastest index 2
    }

    @Test
    public void calibrationSkipsZeroAndUnitless() {
        AxisMap m = AxisMap.resolve(L("t", "z", "y", "x"), new long[] {2, 3, 5, 6}, null);
        Calibration cal = new Calibration();
        TensorOpener.applyCalibration(cal, m, Arrays.asList(2.0, 0.0, 0.5, 0.25), Arrays.asList("s", "", "um", "um"),
                new long[] {1, 1, 1, 1});
        assertEquals(0.25, cal.pixelWidth, 0);
        assertEquals(0.5, cal.pixelHeight, 0);
        assertEquals(1.0, cal.pixelDepth, 0); // z scale 0 -> untouched
        assertEquals(2.0, cal.frameInterval, 0);
        assertEquals("s", cal.getTimeUnit());
        assertEquals("µm", cal.getXUnit());

        Calibration none = new Calibration();
        TensorOpener.applyCalibration(none, m, Arrays.asList(0.0, 0.0, 0.0, 0.0), Arrays.asList("", "", "", ""),
                new long[] {1, 1, 1, 1});
        assertEquals(1.0, none.pixelWidth, 0);
        assertEquals("pixel", none.getUnit());
        assertEquals(0.0, none.frameInterval, 0);
    }

    @Test
    public void calibrationScalesByPyramidLevel() {
        AxisMap m = AxisMap.resolve(L("y", "x"), new long[] {5, 6}, null);
        Calibration cal = new Calibration();
        TensorOpener.applyCalibration(cal, m, Arrays.asList(0.5, 0.25), Arrays.asList("um", "um"), new long[] {4, 2});
        assertEquals(0.5, cal.pixelWidth, 0);
        assertEquals(2.0, cal.pixelHeight, 0);
    }

    @Test
    public void planeSizeGuardSuggestsCoarserLevel() {
        AxisMap m = AxisMap.resolve(L("y", "x"), new long[] {100, 100}, null);
        java.util.List<biopb.tensor.PyramidLevel> pyr = Arrays.asList(
                biopb.tensor.PyramidLevel.newBuilder().addShape(100).addShape(100).build(),
                biopb.tensor.PyramidLevel.newBuilder().addShape(50).addShape(50).build(),
                biopb.tensor.PyramidLevel.newBuilder().addShape(10).addShape(10).build());
        TensorOpener.checkPlaneSize(new long[] {10, 10}, m, 2, pyr, 100, 2); // fits
        try {
            TensorOpener.checkPlaneSize(new long[] {100, 100}, m, 0, pyr, 200, 2);
            fail();
        } catch (PlaneTooLargeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("level 2"));
            assertEquals(10000, e.getPlanePixels());
            assertEquals(200, e.getLimit());
        }
        try {
            TensorOpener.checkPlaneSize(new long[] {10, 10}, m, 2, pyr, 50, 2);
            fail();
        } catch (PlaneTooLargeException e) {
            assertTrue(e.getMessage().contains("no coarser"));
        }
    }

    @Test
    public void overrideReachesRoiPlacement() {
        // labels say t,c but the override says the first axis is z.
        AxisMap m = AxisMap.resolve(L("t", "c", "y", "x"), new long[] {3, 2, 5, 6}, "zcyx");
        ij.ImagePlus imp = TensorOpener.assemble(new FakePlaneSource(2, 3, 1), "x");
        ij.gui.Roi roi = new ij.gui.Roi(0, 0, 2, 2);
        PlaneMap.fromAxisMap(m).place(roi, java.util.Collections.singletonMap(0, 2), imp, null);
        assertEquals(3, roi.getZPosition());
        assertEquals(0, roi.getTPosition());
        // without the override the same pin lands on T (and is out of range for a 1-frame image)
        ij.gui.Roi roi2 = new ij.gui.Roi(0, 0, 2, 2);
        try {
            PlaneMap.fromAxisMap(AxisMap.resolve(L("t", "c", "y", "x"), new long[] {3, 2, 5, 6}, null))
                    .place(roi2, java.util.Collections.singletonMap(0, 2), imp, null);
            fail();
        } catch (RoiConverter.SkipException expected) { }
    }

    @Test
    public void sharedAliasTableAndDuplicatePolicy() {
        assertEquals("z", AxisMap.roleOf("Depth"));
        assertEquals("c", AxisMap.roleOf(" CH "));
        assertEquals(null, AxisMap.roleOf("foo"));
        assertEquals(1, PlaneMap.fromDimLabels(L("depth", "y", "x")) == null ? 0 : 1);
        try { PlaneMap.fromRoles("zzyx"); fail(); } catch (IllegalArgumentException expected) { }
        rejects(L("z", "depth", "y", "x"), new long[] {2, 2, 4, 4}, null, "twice");
    }

    @Test
    public void levelInfoCarriesRolesAndScales() {
        AxisMap m = AxisMap.resolve(L("t", "c", "y", "x"), new long[] {3, 2, 5, 6}, "zcyx");
        TensorOpener.LevelInfo li = new TensorOpener.LevelInfo("id", 1, new long[] {1, 1, 4, 2}, "mean", m);
        assertEquals("zcyx", li.getRoles());
        assertArrayEquals(new double[] {1, 1, 4, 2}, li.getAxisScale(), 0);
        assertEquals(2, li.getScaleX(), 0);
        assertEquals(4, li.getScaleY(), 0);
        assertEquals("-cyx", AxisMap.resolve(L("a", "c", "y", "x"), new long[] {1, 2, 5, 6}, null).roles());
    }

    @Test
    public void rgbWithChannelsRejected() {
        rejects(L("c", "y", "x", "s"), new long[] {2, 4, 4, 3}, null, "RGB");
        AxisMap.resolve(L("c", "y", "x", "s"), new long[] {1, 4, 4, 3}, null); // single channel is fine
        try {
            TensorOpener.assemble(new FakePlaneSource(4, 4, 2, 1, 1, PlaneSource.Kind.RGB), "x");
            fail();
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void dtypeMatchingIsStrict() {
        for (String d : new String[] {"<U5", "|S3", "O", "M8", "m8", "<M8[ns]", "U1", "<F4", "<u3", "", "<c8", "<f1"}) {
            try {
                SdkPlaneSource.kindOf(d, false);
                fail(d);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("Unsupported dtype"));
            }
        }
    }

    @Test
    public void sizeLimits() {
        rejects(L("c", "z", "y", "x"), new long[] {100000, 100000, 4, 4}, null, "stack limit");
        rejects(L("t", "y", "x"), new long[] {3000000000L, 4, 4}, null, "too large");
    }

    @Test
    public void blankOrShortLabelsAreUnlabeled() {
        AxisMap m = AxisMap.resolve(L("", ""), new long[] {5, 6}, null);
        assertEquals(0, m.y); assertEquals(1, m.x);
        m = AxisMap.resolve(L("y"), new long[] {1, 5, 6}, null); // fewer labels than axes
        assertEquals(1, m.y); assertEquals(2, m.x);
        m = AxisMap.resolve(L("", "dim1", " "), new long[] {1, 5, 6}, null);
        assertEquals(2, m.x);
        rejects(L("", "", ""), new long[] {4, 5, 6}, null, "override");
        rejects(L("z"), new long[] {4, 5, 6}, null, "override");
    }

    @Test
    public void cztOverflowIsReportedNotWrapped() {
        rejects(L("c", "z", "t", "y", "x"), new long[] {2000000000L, 2000000000L, 2000000000L, 4, 4}, null, "stack limit");
    }

    @Test
    public void shortScaleHintRejected() {
        AxisMap m = AxisMap.resolve(L("t", "y", "x"), new long[] {2, 5, 6}, null);
        try {
            TensorOpener.applyCalibration(new Calibration(), m, Arrays.asList(1.0, 1.0, 1.0), Arrays.asList("s", "um", "um"),
                    new long[] {1, 2});
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("scale hint"));
        }
        try {
            new TensorOpener.LevelInfo("id", 1, new long[] {1}, "mean", m);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("scale hint"));
        }
    }

    @Test
    public void lenientDimLabelsFirstWins() {
        // lenient path (merged ROI code): no exception, first axis wins
        PlaneMap.fromDimLabels(L("z", "depth", "y", "x"));
    }
}
