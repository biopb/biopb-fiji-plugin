package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.*;

import biopb.image.Ellipse;
import biopb.image.Mask;
import biopb.image.Mesh;
import biopb.image.Point;
import biopb.image.Polygon;
import biopb.image.Polyline;
import biopb.image.ROI;
import biopb.image.Rectangle;
import biopb.image.RoiAnnotation;
import biopb.image.RoiListResult;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.EllipseRoi;
import ij.gui.PointRoi;
import ij.gui.Roi;
import java.util.Arrays;
import org.junit.Test;

public class RoiLoaderTest {
    private static Point pt(double x, double y) {
        return Point.newBuilder().setX((float) x).setY((float) y).build();
    }

    private static RoiAnnotation.Builder ann(String id, ROI.Builder g) {
        return RoiAnnotation.newBuilder().setRoiId(id).setArrayId("arr").setSetName("s").setRoi(g);
    }

    private static ROI.Builder rect(double x0, double y0, double x1, double y1) {
        return ROI.newBuilder().setRectangle(
                Rectangle.newBuilder().setTopLeft(pt(x0, y0)).setBottomRight(pt(x1, y1)));
    }

    private static final PlaneMap TCZ = PlaneMap.fromDimLabels(Arrays.asList("t", "c", "z", "y", "x"));

    private static ImagePlus stack(int c, int z, int t) {
        return IJ.createHyperStack("x", 64, 64, c, z, t, 8);
    }

    private static RoiLoader.Result load(ImagePlus imp, double sx, double sy, boolean trunc,
            RoiAnnotation... as) throws Exception {
        RoiListResult r = RoiListResult.newBuilder().addAllRois(Arrays.asList(as)).setTruncated(trunc).build();
        return RoiLoader.load((a, s) -> r, "arr", "s", imp, TCZ, sx, sy, false);
    }

    @Test
    public void rectangleScaledAndMetadata() throws Exception {
        ImagePlus imp = stack(1, 1, 1);
        RoiAnnotation a = ann("id1", rect(20, 40, 60, 80)).setLabel("cell").setPropsJson("{}").build();
        RoiLoader.Result res = load(imp, 2, 4, false, a);
        assertEquals(1, res.loaded);
        Roi r = imp.getOverlay().get(0);
        java.awt.geom.Rectangle2D b = r.getFloatBounds();
        assertEquals(10, b.getX(), 1e-6);
        assertEquals(10, b.getY(), 1e-6);
        assertEquals(20, b.getWidth(), 1e-6);
        assertEquals(10, b.getHeight(), 1e-6);
        assertEquals("cell", r.getName());
        assertEquals("id1", r.getProperty(RoiConverter.PROP_ROI_ID));
        assertEquals("s", r.getProperty(RoiConverter.PROP_SET_NAME));
        assertEquals("arr", r.getProperty(RoiConverter.PROP_ARRAY_ID));
        assertEquals("{}", r.getProperty(RoiConverter.PROP_PROPS_JSON));
    }

    @Test
    public void polygonPolylinePoint() {
        Roi pg = RoiConverter.toRoi(ROI.newBuilder().setPolygon(Polygon.newBuilder()
                .addPoints(pt(0, 0)).addPoints(pt(20, 0)).addPoints(pt(20, 10))).build(), 2, 2);
        assertEquals(Roi.POLYGON, pg.getType());
        assertEquals(10, pg.getFloatBounds().getWidth(), 1e-6);
        Roi pl = RoiConverter.toRoi(ROI.newBuilder().setPolyline(Polyline.newBuilder()
                .addPoints(pt(0, 0)).addPoints(pt(8, 8)).setWidth(4)).build(), 2, 2);
        assertEquals(Roi.POLYLINE, pl.getType());
        assertEquals(2, pl.getStrokeWidth(), 1e-6);
        Roi p = RoiConverter.toRoi(ROI.newBuilder().setPoint(pt(10, 6)).build(), 2, 2);
        assertTrue(p instanceof PointRoi);
        assertEquals(5, p.getFloatBounds().getX(), 1e-6);
        assertEquals(3, p.getFloatBounds().getY(), 1e-6);
    }

    @Test
    public void ellipseRotation() {
        ROI.Builder g = ROI.newBuilder().setEllipse(Ellipse.newBuilder()
                .setCenter(pt(20, 20)).setRadius(pt(10, 4)));
        Roi e = RoiConverter.toRoi(g.build(), 1, 1);
        assertTrue(e instanceof EllipseRoi);
        java.awt.geom.Rectangle2D b = e.getFloatBounds();
        assertEquals(20, b.getWidth(), 0.5);
        assertEquals(8, b.getHeight(), 0.5);
        // rotated by 90 degrees: width and height swap
        g.getEllipseBuilder().setRotation((float) (Math.PI / 2));
        b = RoiConverter.toRoi(g.build(), 1, 1).getFloatBounds();
        assertEquals(8, b.getWidth(), 0.5);
        assertEquals(20, b.getHeight(), 0.5);
        // radius.y > radius.x, no rotation: tall ellipse
        g = ROI.newBuilder().setEllipse(Ellipse.newBuilder().setCenter(pt(20, 20)).setRadius(pt(4, 10)));
        b = RoiConverter.toRoi(g.build(), 1, 1).getFloatBounds();
        assertEquals(8, b.getWidth(), 0.5);
        assertEquals(20, b.getHeight(), 0.5);
    }

    @Test
    public void pinning() throws Exception {
        ImagePlus imp = stack(3, 4, 5);
        RoiAnnotation unpinned = ann("u", rect(1, 1, 5, 5)).build();
        // axes: 0=t 1=c 2=z
        RoiAnnotation pinned = ann("p", rect(1, 1, 5, 5)).putPlane(0, 2).putPlane(1, 1).putPlane(2, 3).build();
        RoiAnnotation zOnly = ann("z", rect(1, 1, 5, 5)).putPlane(2, 0).build();
        RoiAnnotation yPin = ann("y", rect(1, 1, 5, 5)).putPlane(3, 7).build(); // not c/z/t: ignored
        RoiAnnotation outside = ann("o", rect(1, 1, 5, 5)).putPlane(0, 5).build();
        RoiLoader.Result res = load(imp, 1, 1, false, unpinned, pinned, zOnly, yPin, outside);
        assertEquals(4, res.loaded);
        assertEquals(1, res.skipped);
        assertEquals(Integer.valueOf(1), res.skippedReasons.get("plane outside image"));
        Roi[] r = imp.getOverlay().toArray();
        assertEquals(0, r[0].getPosition());
        assertEquals(2, r[1].getCPosition());
        assertEquals(4, r[1].getZPosition());
        assertEquals(3, r[1].getTPosition());
        assertEquals(0, r[2].getCPosition());
        assertEquals(1, r[2].getZPosition());
        assertEquals(0, r[2].getTPosition());
        assertEquals(0, r[3].getPosition() + r[3].getCPosition() + r[3].getZPosition() + r[3].getTPosition());
    }

    @Test
    public void skipReportingAndTruncation() throws Exception {
        ImagePlus imp = stack(1, 1, 1);
        RoiAnnotation mask = ann("m", ROI.newBuilder().setMask(Mask.getDefaultInstance())).build();
        RoiAnnotation mesh = ann("n", ROI.newBuilder().setMesh(Mesh.getDefaultInstance())).build();
        RoiAnnotation none = ann("e", ROI.newBuilder()).build();
        RoiAnnotation ok = ann("k", rect(0, 0, 3, 3)).build();
        RoiLoader.Result res = load(imp, 1, 1, true, mask, mesh, none, ok);
        assertEquals(1, res.loaded);
        assertEquals(3, res.skipped);
        assertTrue(res.truncated);
        assertEquals(3, res.skippedReasons.size());
        assertTrue(res.skippedReasons.containsKey("unsupported ROI shape 'mask'"));
        try {
            RoiConverter.toRoi(mask.getRoi(), 1, 1);
            fail();
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("supported: rectangle"));
        }
    }

    @Test
    public void keepsExistingOverlayAndPassesArguments() throws Exception {
        ImagePlus imp = stack(1, 1, 1);
        load(imp, 1, 1, false, ann("a", rect(0, 0, 3, 3)).build());
        String[] seen = new String[2];
        RoiLoader.load((a, s) -> {
            seen[0] = a;
            seen[1] = s;
            return RoiListResult.newBuilder().addRois(ann("b", rect(0, 0, 2, 2))).build();
        }, "arrX", "setY", imp, TCZ, 1, 1, false);
        assertEquals("arrX", seen[0]);
        assertEquals("setY", seen[1]);
        assertEquals(2, imp.getOverlay().size());
    }
}
