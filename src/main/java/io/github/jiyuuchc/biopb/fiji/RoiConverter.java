package io.github.jiyuuchc.biopb.fiji;

import biopb.image.Point;
import biopb.image.ROI;
import biopb.image.RoiAnnotation;
import ij.gui.EllipseRoi;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import java.util.List;
import java.util.Locale;

/**
 * Converts {@code biopb.image.ROI} geometry to ImageJ {@link Roi}s. Pure: no image or UI state.
 *
 * <p><b>Coordinate assumption</b> (the proto does not state it): biopb ROI coordinates are in
 * pixel-index space, as numpy/napari use, so an integer coordinate is a pixel centre. ImageJ's
 * continuous coordinates put the centre of pixel {@code i} at {@code i+0.5}. At a pyramid level
 * with scale factors (sx, sy) (level pixel {@code i} covers original indices
 * {@code [i*s, (i+1)*s)}), every coordinate maps to {@code (x + 0.5) / sx}, {@code (y + 0.5) / sy}.
 * Rectangle corners are treated as continuous corner points (the proto does not say whether the
 * bottom-right is inclusive); a rectangle with zero width or height is skipped.
 * This is applied to all vertices, corners and centres; radii and widths scale by the factor alone.
 *
 * <p>Supported arms: rectangle, ellipse, polygon (&ge;3 points), polyline (&ge;2 points) and
 * point. An ellipse is an {@link EllipseRoi} when sx == sy, else a polygon of its exact affine
 * image. Anything else, and malformed geometry, throws {@link SkipException}.
 */
public final class RoiConverter {
    /** Roi property: annotation id. */
    public static final String PROP_ROI_ID = "biopb.roi_id";
    /** Roi property: set (layer) name. */
    public static final String PROP_SET_NAME = "biopb.set_name";
    /** Roi property: array id the annotation belongs to. */
    public static final String PROP_ARRAY_ID = "biopb.array_id";
    /** Roi property: opaque client JSON, when non-empty. */
    public static final String PROP_PROPS_JSON = "biopb.props_json";

    private static final int ELLIPSE_VERTICES = 96;

    private RoiConverter() {
    }

    /** Thrown for an annotation that cannot become a Roi; {@link #reason()} is the skip reason. */
    public static class SkipException extends UnsupportedOperationException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public SkipException(String reason) {
            super(reason);
            this.reason = reason;
        }

        /** Short, stable reason text used to group skips. */
        public String reason() {
            return reason;
        }
    }

    private static boolean bad(double v) {
        return Double.isNaN(v) || Double.isInfinite(v);
    }

    private static void check(boolean ok, String shape) {
        if (!ok) {
            throw new SkipException("malformed " + shape + " geometry");
        }
    }

    private static boolean ok(Point p) {
        return !bad(p.getX()) && !bad(p.getY());
    }

    /**
     * Geometry only.
     *
     * @throws SkipException for mask, mesh, an unset shape, or malformed geometry
     */
    public static Roi toRoi(ROI g, double sx, double sy) {
        switch (g.getShapeCase()) {
            case RECTANGLE: {
                Point a = g.getRectangle().getTopLeft(), b = g.getRectangle().getBottomRight();
                check(ok(a) && ok(b), "rectangle");
                if (a.getX() == b.getX() || a.getY() == b.getY()) {
                    throw new SkipException("zero-size rectangle");
                }
                double x0 = (Math.min(a.getX(), b.getX()) + 0.5) / sx;
                double x1 = (Math.max(a.getX(), b.getX()) + 0.5) / sx;
                double y0 = (Math.min(a.getY(), b.getY()) + 0.5) / sy;
                double y1 = (Math.max(a.getY(), b.getY()) + 0.5) / sy;
                return new Roi(x0, y0, x1 - x0, y1 - y0);
            }
            case ELLIPSE:
                return ellipse(g.getEllipse(), sx, sy);
            case POLYGON: {
                List<Point> pts = g.getPolygon().getPointsList();
                check(pts.size() >= 3 && pts.stream().allMatch(RoiConverter::ok), "polygon");
                return poly(pts, sx, sy, Roi.POLYGON);
            }
            case POLYLINE: {
                List<Point> pts = g.getPolyline().getPointsList();
                float w = g.getPolyline().getWidth();
                check(pts.size() >= 2 && pts.stream().allMatch(RoiConverter::ok) && !bad(w),
                        "polyline");
                Roi r = poly(pts, sx, sy, Roi.POLYLINE);
                if (w > 0) {
                    r.setStrokeWidth(Math.max(1.0, w / ((sx + sy) / 2)));
                }
                return r;
            }
            case POINT: {
                Point p = g.getPoint();
                check(ok(p), "point");
                return new PointRoi((p.getX() + 0.5) / sx, (p.getY() + 0.5) / sy);
            }
            default:
                throw new SkipException("unsupported ROI shape '"
                        + (g.getShapeCase() == ROI.ShapeCase.SHAPE_NOT_SET ? "none"
                                : g.getShapeCase().name().toLowerCase(Locale.ROOT))
                        + "'");
        }
    }

    private static Roi ellipse(biopb.image.Ellipse e, double sx, double sy) {
        double rx = e.getRadius().getX(), ry = e.getRadius().getY();
        double cx = e.getCenter().getX(), cy = e.getCenter().getY(), rot = e.getRotation();
        check(ok(e.getCenter()) && rx >= 0 && ry >= 0 && !bad(rx) && !bad(ry) && !bad(rot),
                "ellipse");
        if (rx == 0 || ry == 0) {
            throw new SkipException("zero-size ellipse");
        }
        // Rotation is from +x toward +y (y down), about the centre, in index space.
        double cos = Math.cos(rot), sin = Math.sin(rot);
        if (sx == sy) {
            double major = Math.max(rx, ry), minor = Math.min(rx, ry);
            double theta = rot + (ry > rx ? Math.PI / 2 : 0);
            double dx = major * Math.cos(theta) / sx, dy = major * Math.sin(theta) / sy;
            double mx = (cx + 0.5) / sx, my = (cy + 0.5) / sy;
            return new EllipseRoi(mx - dx, my - dy, mx + dx, my + dy,
                    major > 0 ? minor / major : 1);
        }
        float[] xs = new float[ELLIPSE_VERTICES], ys = new float[ELLIPSE_VERTICES];
        for (int i = 0; i < ELLIPSE_VERTICES; i++) {
            double phi = 2 * Math.PI * i / ELLIPSE_VERTICES;
            double u = rx * Math.cos(phi), v = ry * Math.sin(phi);
            xs[i] = (float) ((cx + u * cos - v * sin + 0.5) / sx);
            ys[i] = (float) ((cy + u * sin + v * cos + 0.5) / sy);
        }
        return new PolygonRoi(xs, ys, ELLIPSE_VERTICES, Roi.POLYGON);
    }

    /**
     * Annotation to Roi: geometry plus name (the label, when set) and the properties
     * {@link #PROP_ROI_ID}, {@link #PROP_SET_NAME}, {@link #PROP_ARRAY_ID}, {@link #PROP_PROPS_JSON}
     * for a later write-back. Plane position is set by {@link RoiLoader}.
     */
    public static Roi toRoi(RoiAnnotation a, double sx, double sy) {
        Roi r = toRoi(a.getRoi(), sx, sy);
        if (!a.getLabel().isEmpty()) {
            r.setName(a.getLabel());
        }
        r.setProperty(PROP_ROI_ID, a.getRoiId());
        r.setProperty(PROP_SET_NAME, a.getSetName());
        r.setProperty(PROP_ARRAY_ID, a.getArrayId());
        if (!a.getPropsJson().isEmpty()) {
            r.setProperty(PROP_PROPS_JSON, a.getPropsJson());
        }
        return r;
    }

    private static Roi poly(List<Point> pts, double sx, double sy, int type) {
        float[] xs = new float[pts.size()], ys = new float[pts.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = (float) ((pts.get(i).getX() + 0.5) / sx);
            ys[i] = (float) ((pts.get(i).getY() + 0.5) / sy);
        }
        return new PolygonRoi(xs, ys, xs.length, type);
    }
}
