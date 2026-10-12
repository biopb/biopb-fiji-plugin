package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.PyramidLevel;
import biopb.tensor.TensorDescriptor;
import biopb.tensor.TensorFlightClient;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;

import java.util.Arrays;
import java.util.List;

/**
 * Opens a server tensor as a lazy hyperstack {@link ImagePlus} backed by a {@link TensorVirtualStack}.
 *
 * <p>Axes: t, c, z, y, x (and an optional size-3 s axis for RGB) are supported, in whatever order the
 * descriptor declares; data is never transposed. Tensors whose axes are all unlabeled ({@code dim0..})
 * are treated as ending in (y, x); with more than two non-singleton unlabeled axes the caller must
 * pass an axis override (see {@link AxisMap#resolve}).
 *
 * <p>Pixel types: uint8, uint16 and float32 open natively; all other numeric dtypes are converted to
 * float32 plane by plane (a deliberate choice: ImageJ has no other native types, and float keeps
 * signed/wide values representable). uint8 + sample axis of 3 opens as RGB.
 */
public final class TensorOpener {
    /** Default per-plane pixel limit: 2^28 (16384 x 16384). ImageJ's hard limit is {@link Integer#MAX_VALUE}. */
    public static final long DEFAULT_MAX_PLANE_PIXELS = 1L << 28;

    private TensorOpener() {}

    /**
     * Which pyramid level an {@link ImagePlus} shows, kept as the ImagePlus property
     * {@value #PROPERTY}. ROI annotations are stored in level-0 pixels; divide level-0 x/y by
     * {@link #getScaleX()}/{@link #getScaleY()} to get pixels of this image.
     */
    public static final class LevelInfo {
        /** ImagePlus property key under which the instance is stored. */
        public static final String PROPERTY = "biopb.level";
        private final String arrayId;
        private final int level;
        private final long[] scaleHint;
        private final String reductionMethod;
        private final double scaleX, scaleY;
        private final AxisMap axes;

        LevelInfo(String arrayId, int level, long[] scaleHint, String reductionMethod, AxisMap map) {
            if (scaleHint.length != map.ndim) {
                throw new IllegalArgumentException("scale hint has " + scaleHint.length + " entries but the tensor has "
                        + map.ndim + " axes");
            }
            this.axes = map;
            this.arrayId = arrayId;
            this.level = level;
            this.scaleHint = scaleHint.clone();
            this.reductionMethod = reductionMethod;
            this.scaleX = scaleHint[map.x];
            this.scaleY = scaleHint[map.y];
        }

        /** The level info of an image opened by {@link TensorOpener}, or null. */
        public static LevelInfo of(ImagePlus imp) {
            Object o = imp == null ? null : imp.getProperty(PROPERTY);
            return o instanceof LevelInfo ? (LevelInfo) o : null;
        }

        /** The tensor id. */
        public String getArrayId() { return arrayId; }
        /** Pyramid level index (0 = full resolution). */
        public int getLevel() { return level; }
        /** Per-axis downsampling factors in descriptor order (all 1 at level 0). */
        public long[] getScaleHint() { return scaleHint.clone(); }
        /** Reduction method used for this level (may be empty at level 0). */
        public String getReductionMethod() { return reductionMethod; }
        /** The roles resolved for the tensor's axes (honouring any axis override), e.g. {@code "tczyx"}. */
        public String getRoles() { return axes.roles(); }
        /** The resolved axis map. */
        public AxisMap getAxisMap() { return axes; }
        /**
         * Per-axis downsampling factors as doubles, aligned with the descriptor's axes; this is the
         * {@code axisScale} argument of {@link PlaneMap}{@code .place}.
         */
        public double[] getAxisScale() {
            double[] a = new double[scaleHint.length];
            for (int i = 0; i < a.length; i++) a[i] = scaleHint[i];
            return a;
        }
        /** Plane placement for ROIs, built from the resolved roles (so an override applies). */
        public PlaneMap toPlaneMap() { return PlaneMap.fromAxisMap(axes); }
        /** Downsampling factor along x. */
        public double getScaleX() { return scaleX; }
        /** Downsampling factor along y. */
        public double getScaleY() { return scaleY; }
    }

    /** Opens {@code arrayId} at {@code level} with the default plane limit; see {@link #open(TensorFlightClient, String, int, String, long)}. */
    public static ImagePlus open(TensorFlightClient client, String arrayId, int level, String axisOverride) {
        return open(client, arrayId, level, axisOverride, DEFAULT_MAX_PLANE_PIXELS);
    }

    /**
     * Opens a tensor as a virtual hyperstack. Nothing but the first plane is read here.
     *
     * @param client         connected client
     * @param arrayId        tensor id
     * @param level          pyramid level (0 = full resolution)
     * @param axisOverride   role string per axis (e.g. {@code "tzyx"}) or null to use the labels
     *
     * <p>Blocking (server round trips and one plane read per channel): call from a worker thread,
     * not the EDT.
     *
     * @param maxPlanePixels refuse planes with more pixels than this (and always more than 2^31-1)
     * @return the image; its {@link LevelInfo} is available via {@link LevelInfo#of}
     * @throws IllegalArgumentException    for unsupported axes/dtypes or an invalid level
     * @throws PlaneTooLargeException      if one plane exceeds the limit (names a coarser level if any)
     */
    public static ImagePlus open(TensorFlightClient client, String arrayId, int level, String axisOverride,
                                 long maxPlanePixels) {
        TensorDescriptor d = client.getDescriptor(arrayId);
        List<PyramidLevel> pyramid = d.getPyramidList();
        long[] shape0 = toArray(d.getShapeList());
        long[] shape = shape0;
        long[] hint = ones(shape.length);
        String method = "";
        if (level < 0 || (level > 0 && level >= pyramid.size())) {
            throw new IllegalArgumentException("Level " + level + " not available; tensor has "
                    + Math.max(1, pyramid.size()) + " level(s)");
        }
        if (level > 0) {
            PyramidLevel p = pyramid.get(level);
            hint = toArray(p.getScaleHintList());
            method = p.getReductionMethod();
            shape = toArray(p.getShapeList());
        }
        // Roles are resolved once, on the level-0 shape, so singleton-ness never depends on the level.
        AxisMap map = AxisMap.resolve(d.getDimLabelsList(), shape0, axisOverride);
        PlaneSource.Kind kind = SdkPlaneSource.kindOf(d.getDtype(), map.s >= 0); // rejects bad dtypes now
        checkPlaneSize(shape, map, level, pyramid, maxPlanePixels, kind.bytesPerPixel());

        RandomAccessibleInterval<?> tensor = level == 0
                ? client.getTensor(arrayId)
                : client.getTensor(arrayId, hint, method);
        @SuppressWarnings("unchecked")
        RandomAccessibleInterval<? extends RealType<?>> rai = (RandomAccessibleInterval<? extends RealType<?>>) tensor;
        PlaneSource src = new SdkPlaneSource(rai, map, d.getDtype());

        String title = level == 0 ? arrayId : arrayId + " [level " + level + "]";
        ImagePlus imp = assemble(src, title);
        applyCalibration(imp.getCalibration(), map, d.getPhysicalScaleList(), d.getPhysicalUnitList(), hint);
        imp.setProperty(LevelInfo.PROPERTY, new LevelInfo(arrayId, level, hint, method, map));
        return imp;
    }

    /**
     * Wraps a plane source in a virtual-stack hyperstack (composite mode when it has several channels).
     * Server-independent; this is what {@link #open} uses once the source exists. Reads the first
     * plane of every channel to set display ranges, synchronously: call from a worker thread,
     * never the EDT.
     */
    public static ImagePlus assemble(PlaneSource src, String title) {
        if (src.getKind() == PlaneSource.Kind.RGB && src.getSizeC() > 1) {
            throw new IllegalArgumentException("RGB with " + src.getSizeC() + " channels is not supported "
                    + "(ImageJ cannot make a composite of RGB images)");
        }
        TensorVirtualStack stack = new TensorVirtualStack(src);
        int c = src.getSizeC(), z = src.getSizeZ(), t = src.getSizeT();
        boolean ranged = src.getKind() == PlaneSource.Kind.GRAY16 || src.getKind() == PlaneSource.Kind.GRAY32;
        double[][] ranges = new double[c][];
        if (ranged && c > 1) {
            for (int ch = 0; ch < c; ch++) ranges[ch] = stack.getDisplayRange(ch); // one plane read per channel
        }
        ImagePlus imp = new ImagePlus(title, stack); // reads (and range-samples) the first plane
        imp.setDimensions(c, z, t);
        imp.setOpenAsHyperStack(c > 1 || t > 1);
        if (c > 1) {
            CompositeImage ci = new CompositeImage(imp, IJ.COMPOSITE);
            if (ranged) {
                // Write each channel's LUT range directly: no setC, so nothing is re-rendered or re-read.
                for (int ch = 1; ch <= c; ch++) {
                    ij.process.LUT lut = ci.getChannelLut(ch);
                    lut.min = ranges[ch - 1][0];
                    lut.max = ranges[ch - 1][1];
                    ci.setChannelLut(lut, ch);
                }
            }
            imp = ci;
        }
        return imp;
    }

    /**
     * Fills pixel size (x, y, z) and frame interval from descriptor physical scales. A scale of 0
     * (or negative) means "not calibrated" and is skipped, as is a scale with an empty unit.
     * Scales are per level-0 pixel, so they are multiplied by the level's scale hint.
     */
    static void applyCalibration(Calibration cal, AxisMap map, List<Double> scales, List<String> units,
                                 long[] scaleHint) {
        if (scaleHint.length != map.ndim) {
            throw new IllegalArgumentException("scale hint has " + scaleHint.length + " entries but the tensor has "
                    + map.ndim + " axes");
        }
        double sx = scaleAt(scales, units, map.x, scaleHint), sy = scaleAt(scales, units, map.y, scaleHint);
        double sz = map.z >= 0 ? scaleAt(scales, units, map.z, scaleHint) : 0;
        double st = map.t >= 0 ? scaleAt(scales, units, map.t, scaleHint) : 0;
        if (sx > 0) { cal.pixelWidth = sx; cal.setXUnit(units.get(map.x)); }
        if (sy > 0) { cal.pixelHeight = sy; cal.setYUnit(units.get(map.y)); }
        if (sz > 0) { cal.pixelDepth = sz; cal.setZUnit(units.get(map.z)); }
        if (st > 0) { cal.frameInterval = st; cal.setTimeUnit(units.get(map.t)); }
    }

    private static double scaleAt(List<Double> scales, List<String> units, int axis, long[] hint) {
        if (axis >= scales.size() || axis >= units.size()) return 0;
        double s = scales.get(axis);
        if (!(s > 0) || units.get(axis).trim().isEmpty()) return 0;
        return s * hint[axis];
    }

    static void checkPlaneSize(long[] shape, AxisMap map, int level, List<PyramidLevel> pyramid, long limit,
                               int bytesPerPixel) {
        long px = planePixels(shape, map);
        long max = TensorVirtualStack.maxPlanePixels(limit, bytesPerPixel);
        if (px <= max) return;
        String hintText = "no coarser pyramid level is available";
        for (int k = level + 1; k < pyramid.size(); k++) {
            if (planePixels(toArray(pyramid.get(k).getShapeList()), map) <= max) {
                hintText = "try pyramid level " + k;
                break;
            }
        }
        throw new PlaneTooLargeException("A single plane at level " + level + " has " + px + " pixels (limit " + max
                + ", from the plane limit, ImageJ's array size and available heap); " + hintText, px, max);
    }

    private static long planePixels(long[] shape, AxisMap map) {
        return shape[map.x] * shape[map.y];
    }

    private static long[] toArray(List<Long> l) {
        long[] a = new long[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static long[] ones(int n) {
        long[] a = new long[n];
        Arrays.fill(a, 1);
        return a;
    }
}
