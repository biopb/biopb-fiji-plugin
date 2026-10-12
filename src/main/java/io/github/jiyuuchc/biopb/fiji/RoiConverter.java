package io.github.jiyuuchc.biopb.fiji;

import biopb.image.Point;
import biopb.image.ROI;
import biopb.image.RoiAnnotation;
import ij.gui.EllipseRoi;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import java.util.List;

/**
 * Converts {@code biopb.image.ROI} geometry to ImageJ {@link Roi}s. Pure: no image or UI state.
 *
 * <p>Server geometry is level-0 pixel coordinates; a view at a pyramid level with scale factors
 * (sx, sy) takes {@code x / sx}, {@code y / sy}. Coordinates are otherwise passed to ImageJ
 * unchanged (ImageJ's origin is the top-left corner of the first pixel).
 *
 * <p>Supported arms: rectangle, ellipse (rotation honoured, via {@link EllipseRoi}), polygon,
 * polyline, point. {@code mask} and {@code mesh} (and an unset shape) are not stored as
 * annotations and throw {@link UnsupportedOperationException}.
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

    private RoiConverter() {
    }

    /**
     * Geometry only.
     *
     * @throws UnsupportedOperationException for mask, mesh or an unset shape; the message
     *         names the arm and lists the supported ones
     */
    public static Roi toRoi(ROI g, double sx, double sy) {
        switch (g.getShapeCase()) {
            case RECTANGLE: {
                Point a = g.getRectangle().getTopLeft(), b = g.getRectangle().getBottomRight();
                double x0 = Math.min(a.getX(), b.getX()) / sx, x1 = Math.max(a.getX(), b.getX()) / sx;
                double y0 = Math.min(a.getY(), b.getY()) / sy, y1 = Math.max(a.getY(), b.getY()) / sy;
                return new Roi(x0, y0, x1 - x0, y1 - y0);
            }
            case ELLIPSE: {
                biopb.image.Ellipse e = g.getEllipse();
                double rx = e.getRadius().getX(), ry = e.getRadius().getY();
                double cx = e.getCenter().getX(), cy = e.getCenter().getY();
                // Major axis endpoints; rotation is from +x toward +y (y down in image space).
                double major = Math.max(rx, ry), minor = Math.min(rx, ry);
                double theta = e.getRotation() + (ry > rx ? Math.PI / 2 : 0);
                double dx = major * Math.cos(theta), dy = major * Math.sin(theta);
                double aspect = major > 0 ? minor / major : 1;
                return new EllipseRoi((cx - dx) / sx, (cy - dy) / sy, (cx + dx) / sx, (cy + dy) / sy,
                        aspect);
            }
            case POLYGON:
                return poly(g.getPolygon().getPointsList(), sx, sy, Roi.POLYGON);
            case POLYLINE: {
                Roi r = poly(g.getPolyline().getPointsList(), sx, sy, Roi.POLYLINE);
                if (g.getPolyline().getWidth() > 0) {
                    r.setStrokeWidth(g.getPolyline().getWidth() / (float) ((sx + sy) / 2));
                }
                return r;
            }
            case POINT:
                return new PointRoi(g.getPoint().getX() / sx, g.getPoint().getY() / sy);
            default:
                throw new UnsupportedOperationException("unsupported ROI shape '"
                        + (g.getShapeCase() == ROI.ShapeCase.SHAPE_NOT_SET ? "none"
                                : g.getShapeCase().name().toLowerCase())
                        + "'; supported: rectangle, ellipse, polygon, polyline, point");
        }
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
            xs[i] = (float) (pts.get(i).getX() / sx);
            ys[i] = (float) (pts.get(i).getY() / sy);
        }
        return new PolygonRoi(xs, ys, xs.length, type);
    }
}
