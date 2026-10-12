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
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import java.awt.GraphicsEnvironment;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import org.junit.Assume;
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

    private static ROI.Builder ell(double cx, double cy, double rx, double ry, double rot) {
        return ROI.newBuilder().setEllipse(Ellipse.newBuilder().setCenter(pt(cx, cy))
                .setRadius(pt(rx, ry)).setRotation((float) rot));
    }

    private static final PlaneMap TCZ = PlaneMap.fromDimLabels(Arrays.asList("t", "c", "z", "y", "x"));

    private static ImagePlus stack(int c, int z, int t) {
        return IJ.createHyperStack("x", 64, 64, c, z, t, 8);
    }

    private static RoiLoader.Result load(ImagePlus imp, double sx, double sy, boolean trunc,
            RoiAnnotation... as) throws Exception {
        RoiListResult r = RoiListResult.newBuilder().addAllRois(Arrays.asList(as)).setTruncated(trunc).build();
        return RoiLoader.load((a, s) -> r, "arr", "s", imp, TCZ, sx, sy);
    }

    private static void assertBounds(Roi r, double cx, double cy, double w, double h, double tol) {
        Rectangle2D b = r.getFloatPolygon().getFloatBounds(); // true outline, not pixel-snapped
        assertEquals(cx, b.getCenterX(), tol);
        assertEquals(cy, b.getCenterY(), tol);
        assertEquals(w, b.getWidth(), tol);
        assertEquals(h, b.getHeight(), tol);
    }

    @Test
    public void rectangleScaledPixelCentreAndMetadata() throws Exception {
        ImagePlus imp = stack(1, 1, 1);
        RoiAnnotation a = ann("id1", rect(20, 40, 60, 80)).setLabel("cell").setPropsJson("{}").build();
        RoiLoader.Result res = load(imp, 2, 4, false, a);
        assertEquals(1, res.loaded);
        assertNull(imp.getOverlay()); // load does not touch the image
        assertEquals(1, res.applyTo(imp, false));
        Roi r = imp.getOverlay().get(0);
        Rectangle2D b = r.getFloatBounds();
        assertEquals(10.25, b.getX(), 1e-6);
        assertEquals(10.125, b.getY(), 1e-6);
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
        assertEquals(0.25, pg.getFloatBounds().getX(), 1e-6);
        Roi pl = RoiConverter.toRoi(ROI.newBuilder().setPolyline(Polyline.newBuilder()
                .addPoints(pt(0, 0)).addPoints(pt(8, 8)).setWidth(8)).build(), 2, 2);
        assertEquals(Roi.POLYLINE, pl.getType());
        assertEquals(4, pl.getStrokeWidth(), 1e-6);
        Roi thin = RoiConverter.toRoi(ROI.newBuilder().setPolyline(Polyline.newBuilder()
                .addPoints(pt(0, 0)).addPoints(pt(8, 8)).setWidth(1)).build(), 4, 4);
        assertEquals(1, thin.getStrokeWidth(), 1e-6);
        Roi zero = RoiConverter.toRoi(ROI.newBuilder().setPolyline(Polyline.newBuilder()
                .addPoints(pt(0, 0)).addPoints(pt(8, 8))).build(), 1, 1);
        assertTrue(zero.getStrokeWidth() <= 1);
        Roi p = RoiConverter.toRoi(ROI.newBuilder().setPoint(pt(10, 6)).build(), 2, 2);
        assertTrue(p instanceof PointRoi);
        assertEquals(5.25, p.getFloatPolygon().xpoints[0], 1e-6);
        assertEquals(3.25, p.getFloatPolygon().ypoints[0], 1e-6);
    }

    @Test
    public void ellipseGeometry() {
        double h = Math.sqrt(58); // half extent of rx=10, ry=4 rotated 45 degrees
        Roi e = RoiConverter.toRoi(ell(20, 20, 10, 4, 0).build(), 1, 1);
        assertTrue(e instanceof EllipseRoi);
        assertBounds(e, 20.5, 20.5, 20, 8, 0.3);
        e = RoiConverter.toRoi(ell(20, 20, 10, 4, Math.PI / 4).build(), 1, 1);
        assertBounds(e, 20.5, 20.5, 2 * h, 2 * h, 0.3);
        // offset centre, level scale 2
        e = RoiConverter.toRoi(ell(30, 10, 4, 10, 0).build(), 2, 2);
        assertBounds(e, 15.25, 5.25, 4, 10, 0.3);
    }

    @Test
    public void ellipseAnisotropicScale() {
        double h = Math.sqrt(58);
        Roi e = RoiConverter.toRoi(ell(20, 20, 10, 4, Math.PI / 4).build(), 2, 1);
        assertTrue(e instanceof PolygonRoi);
        assertTrue(e.getFloatPolygon().npoints >= 72);
        assertBounds(e, 10.25, 20.5, 2 * h / 2, 2 * h, 0.1);
        e = RoiConverter.toRoi(ell(20, 20, 10, 4, 0).build(), 2, 1);
        assertBounds(e, 10.25, 20.5, 10, 8, 0.1);
        e = RoiConverter.toRoi(ell(20, 20, 10, 4, 0).build(), 1, 4);
        assertBounds(e, 20.5, 5.125, 20, 2, 0.1);
    }

    @Test
    public void pinning() throws Exception {
        ImagePlus imp = stack(3, 4, 5);
        RoiAnnotation unpinned = ann("u", rect(1, 1, 5, 5)).build();
        // axes: 0=t 1=c 2=z
        RoiAnnotation pinned = ann("p", rect(1, 1, 5, 5)).putPlane(0, 2).putPlane(1, 1).putPlane(2, 3).build();
        RoiAnnotation zOnly = ann("z", rect(1, 1, 5, 5)).putPlane(2, 0).build();
        RoiAnnotation yPin = ann("y", rect(1, 1, 5, 5)).putPlane(3, 7).build(); // not c/z/t
        RoiAnnotation unknown = ann("k", rect(1, 1, 5, 5)).putPlane(9, 0).build();
        RoiAnnotation outside = ann("o", rect(1, 1, 5, 5)).putPlane(0, 5).build();
        RoiLoader.Result res = load(imp, 1, 1, false, unpinned, pinned, zOnly, yPin, unknown, outside);
        assertEquals(3, res.loaded);
        assertEquals(3, res.skipped);
        assertEquals(Integer.valueOf(1), res.skippedReasons.get("plane outside image"));
        assertEquals(Integer.valueOf(2), res.skippedReasons.get("pinned axis is not t/c/z"));
        Roi[] r = res.rois.toArray(new Roi[0]);
        assertEquals(0, r[0].getPosition());
        assertEquals(2, r[1].getCPosition());
        assertEquals(4, r[1].getZPosition());
        assertEquals(3, r[1].getTPosition());
        assertEquals(0, r[2].getCPosition());
        assertEquals(1, r[2].getZPosition());
        assertEquals(0, r[2].getTPosition());
    }

    @Test
    public void nonHyperstackUsesStackIndex() throws Exception {
        ImagePlus imp = IJ.createImage("x", "8-bit", 64, 64, 4);
        assertFalse(imp.isHyperStack());
        RoiLoader.Result res = load(imp, 1, 1, false,
                ann("a", rect(1, 1, 5, 5)).putPlane(2, 2).build(),
                ann("b", rect(1, 1, 5, 5)).putPlane(2, 4).build());
        assertEquals(1, res.loaded);
        assertEquals(3, res.rois.get(0).getPosition());
        assertEquals(Integer.valueOf(1), res.skippedReasons.get("plane outside image"));
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
        } catch (RoiConverter.SkipException e) {
            assertEquals("unsupported ROI shape 'mask'", e.reason());
        }
    }

    @Test
    public void malformedGeometryIsSkipped() throws Exception {
        ImagePlus imp = stack(1, 1, 1);
        RoiAnnotation poly2 = ann("a", ROI.newBuilder().setPolygon(Polygon.newBuilder()
                .addPoints(pt(0, 0)).addPoints(pt(5, 5)))).build();
        RoiAnnotation line1 = ann("b", ROI.newBuilder().setPolyline(Polyline.newBuilder()
                .addPoints(pt(0, 0)))).build();
        RoiAnnotation nan = ann("c", ROI.newBuilder().setPoint(pt(Double.NaN, 1))).build();
        RoiAnnotation nanRect = ann("d", rect(0, Double.NaN, 3, 3)).build();
        RoiAnnotation negRadius = ann("e", ell(5, 5, -1, 2, 0)).build();
        RoiLoader.Result res = load(imp, 1, 1, false, poly2, line1, nan, nanRect, negRadius,
                ann("ok", rect(0, 0, 3, 3)).build());
        assertEquals(1, res.loaded);
        assertEquals(5, res.skipped);
        assertEquals(Integer.valueOf(1), res.skippedReasons.get("malformed polygon geometry"));
        assertEquals(Integer.valueOf(1), res.skippedReasons.get("malformed polyline geometry"));
        assertEquals(Integer.valueOf(1), res.skippedReasons.get("malformed point geometry"));
        assertEquals(Integer.valueOf(1), res.skippedReasons.get("malformed rectangle geometry"));
        assertEquals(Integer.valueOf(1), res.skippedReasons.get("malformed ellipse geometry"));
    }

    @Test
    public void passesArgumentsAndDedupesOnApply() throws Exception {
        ImagePlus imp = stack(1, 1, 1);
        String[] seen = new String[2];
        RoiSource src = (a, s) -> {
            seen[0] = a;
            seen[1] = s;
            return RoiListResult.newBuilder().addRois(ann("b", rect(0, 0, 2, 2)))
                    .addRois(ann("c", rect(0, 0, 2, 2))).build();
        };
        RoiLoader.Result res = RoiLoader.load(src, "arrX", "setY", imp, TCZ, 1, 1);
        assertEquals("arrX", seen[0]);
        assertEquals("setY", seen[1]);
        assertEquals(2, res.applyTo(imp, false));
        assertEquals(0, res.applyTo(imp, false)); // same ids and set: no duplicates
        assertEquals(2, imp.getOverlay().size());
        // same id in another set is a different annotation
        RoiLoader.Result other = load(imp, 1, 1, false,
                ann("b", rect(0, 0, 2, 2)).setSetName("other").build());
        assertEquals(1, other.applyTo(imp, false));
        assertEquals(3, imp.getOverlay().size());
    }

    @Test
    public void roiManagerPath() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        ImagePlus imp = stack(1, 1, 1);
        RoiLoader.Result res = load(imp, 1, 1, false, ann("a", rect(0, 0, 3, 3)).build());
        try {
            assertEquals(1, res.applyTo(imp, true));
            assertNull(imp.getOverlay()); // manager instead of overlay
            assertEquals(0, res.applyTo(imp, true)); // deduped against the manager
            RoiManager inst = RoiManager.getInstance2();
            assertNotNull(inst);
            assertTrue(inst.getCount() >= 1);
        } finally {
            RoiManager inst = RoiManager.getInstance2();
            if (inst != null) {
                inst.reset();
                inst.close();
            }
        }
    }

    @Test
    public void plainStacksAnyAxis() throws Exception {
        ImagePlus imp = IJ.createImage("x", "8-bit", 64, 64, 5);
        PlaneMap t = PlaneMap.fromDimLabels(Arrays.asList("t", "y", "x"));
        PlaneMap c = PlaneMap.fromDimLabels(Arrays.asList("c", "y", "x"));
        RoiListResult r = RoiListResult.newBuilder()
                .addRois(ann("a", rect(1, 1, 5, 5)).putPlane(0, 3))
                .addRois(ann("b", rect(1, 1, 5, 5)).putPlane(0, 5)).build();
        for (PlaneMap pm : new PlaneMap[] {t, c, TCZ}) {
            RoiLoader.Result res = RoiLoader.load((a, s2) -> r, "arr", "s", imp, pm, 1, 1);
            assertEquals(1, res.loaded);
            assertEquals(4, res.rois.get(0).getPosition());
            assertEquals(Integer.valueOf(1), res.skippedReasons.get("plane outside image"));
        }
        // z stack: axis 2 in TCZ
        RoiLoader.Result z = load(imp, 1, 1, false, ann("z", rect(1, 1, 5, 5)).putPlane(2, 1).build());
        assertEquals(2, z.rois.get(0).getPosition());
        // zero pin on another axis is fine; two axes > 0 conflict
        RoiLoader.Result mix = load(imp, 1, 1, false,
                ann("m", rect(1, 1, 5, 5)).putPlane(0, 0).putPlane(2, 2).build(),
                ann("n", rect(1, 1, 5, 5)).putPlane(0, 1).putPlane(2, 2).build());
        assertEquals(1, mix.loaded);
        assertEquals(3, mix.rois.get(0).getPosition());
        assertEquals(Integer.valueOf(1), mix.skippedReasons.get("conflicting pins on a plain stack"));
    }

    @Test
    public void downsampledAxisScale() throws Exception {
        ImagePlus imp = stack(1, 4, 1); // z downsampled 2x from 8 planes
        RoiListResult r = RoiListResult.newBuilder()
                .addRois(ann("a", rect(1, 1, 5, 5)).putPlane(2, 5))
                .addRois(ann("b", rect(1, 1, 5, 5)).putPlane(2, 8)).build();
        double[] sc = {1, 1, 2, 1, 1};
        RoiLoader.Result res = RoiLoader.load((a, s) -> r, "arr", "s", imp, TCZ, sc, 1, 1);
        assertEquals(1, res.loaded);
        assertEquals(3, res.rois.get(0).getZPosition()); // floor(5/2)=2 -> plane 3
        assertEquals(1, res.skipped);
    }

    @Test
    public void emptyIdNotDeduped() throws Exception {
        ImagePlus imp = stack(1, 1, 1);
        RoiLoader.Result res = load(imp, 1, 1, false, ann("", rect(0, 0, 3, 3)).build(),
                ann("", rect(0, 0, 4, 4)).build());
        assertEquals(2, res.applyTo(imp, false));
        assertEquals(2, res.applyTo(imp, false));
        assertEquals(4, imp.getOverlay().size());
        // array_id is part of the key
        RoiLoader.Result a = load(imp, 1, 1, false, ann("q", rect(0, 0, 3, 3)).build());
        RoiLoader.Result b = load(imp, 1, 1, false,
                ann("q", rect(0, 0, 3, 3)).setArrayId("other").build());
        assertEquals(1, a.applyTo(imp, false));
        assertEquals(1, b.applyTo(imp, false));
        assertEquals(0, a.applyTo(imp, false));
    }

    @Test
    public void zeroSizeSkipped() throws Exception {
        RoiLoader.Result res = load(stack(1, 1, 1), 1, 1, false,
                ann("a", rect(0, 0, 0, 5)).build(), ann("b", rect(0, 0, 5, 0)).build(),
                ann("c", ell(5, 5, 0, 2, 0)).build(), ann("d", ell(5, 5, 2, 0, 0)).build());
        assertEquals(0, res.loaded);
        assertEquals(Integer.valueOf(2), res.skippedReasons.get("zero-size rectangle"));
        assertEquals(Integer.valueOf(2), res.skippedReasons.get("zero-size ellipse"));
    }

    @Test
    public void nullPlaneMapSkipsPinnedButLoadsUnpinned() throws Exception {
        RoiListResult r = RoiListResult.newBuilder()
                .addRois(ann("a", rect(1, 1, 5, 5)).build())
                .addRois(ann("b", rect(1, 1, 5, 5)).putPlane(0, 1).build()).build();
        RoiLoader.Result res = RoiLoader.load((a, s2) -> r, "arr", "s", stack(1, 1, 1), null, 1, 1);
        assertEquals(1, res.loaded);
        assertEquals(1, res.skipped);
    }
}
