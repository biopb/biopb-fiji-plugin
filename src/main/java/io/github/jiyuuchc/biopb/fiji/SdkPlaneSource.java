package io.github.jiyuuchc.biopb.fiji;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

import java.util.Locale;

/**
 * A {@link PlaneSource} over the lazy {@link RandomAccessibleInterval} returned by the biopb SDK
 * ({@code TensorFlightClient.getTensor}), whose dimensions are in descriptor order.
 *
 * <p>The SDK exposes no bulk access to its cell arrays, so a plane is copied by iterating a localizing
 * cursor over a 2D hyperslice and scattering each value to its row-major position. Dtype handling: uint8 -&gt; byte, uint16 -&gt; short,
 * float32 -&gt; float; every other numeric dtype (int8/16/32/64, uint32/64, float16/64, bool) is
 * converted to float32 (values beyond 2^24 lose integer precision). uint8 with a size-3 sample
 * axis becomes packed RGB.
 */
public class SdkPlaneSource implements PlaneSource {
    private final RandomAccessibleInterval<? extends RealType<?>> rai;
    private final AxisMap map;
    private final Kind kind;
    private final int w, h, sizeC, sizeZ, sizeT;

    /**
     * @param rai   tensor image, dimensions in descriptor order
     * @param map   role of each axis
     * @param dtype numpy-style dtype string from the descriptor, e.g. {@code "<u2"}
     * @throws IllegalArgumentException for an unparsable dtype, or a sample axis with a non-uint8 dtype
     */
    public SdkPlaneSource(RandomAccessibleInterval<? extends RealType<?>> rai, AxisMap map, String dtype) {
        this.rai = rai;
        this.map = map;
        this.kind = kindOf(dtype, map.s >= 0);
        long[] shape = new long[rai.numDimensions()];
        rai.dimensions(shape);
        long px = shape[map.x] * shape[map.y];
        if (shape[map.x] > Integer.MAX_VALUE || shape[map.y] > Integer.MAX_VALUE || px > Integer.MAX_VALUE) {
            throw new PlaneTooLargeException("Plane of " + shape[map.x] + " x " + shape[map.y]
                    + " pixels is too large for ImageJ; use a coarser pyramid level", px, Integer.MAX_VALUE);
        }
        this.w = (int) shape[map.x];
        this.h = (int) shape[map.y];
        long c = map.c >= 0 ? shape[map.c] : 1, z = map.z >= 0 ? shape[map.z] : 1, t = map.t >= 0 ? shape[map.t] : 1;
        if (c > Integer.MAX_VALUE || z > Integer.MAX_VALUE || t > Integer.MAX_VALUE
                || c * z > Integer.MAX_VALUE || c * z * t > Integer.MAX_VALUE || c * z * t < 0) {
            throw new IllegalArgumentException("c*z*t = " + c + "*" + z + "*" + t + " exceeds ImageJ's stack limit of 2^31-1");
        }
        this.sizeC = (int) c;
        this.sizeZ = (int) z;
        this.sizeT = (int) t;
    }

    /**
     * Maps a numpy dtype string to the plane format used for it (see class doc). Matching is
     * case-sensitive and accepts only numeric kinds: {@code [<>|=]?} followed by u/i/f with size
     * 1/2/4/8 (f also 2) or {@code b1}; anything else ({@code <U5}, {@code |S3}, {@code O},
     * {@code M8}, {@code m8}, ...) is rejected.
     */
    static Kind kindOf(String dtype, boolean hasSamples) {
        String d = dtype == null ? "" : dtype.trim();
        if (!d.matches("[<>|=]?([ui][1248]|f[248]|b1)")) {
            throw new IllegalArgumentException("Unsupported dtype \"" + dtype + "\" (numeric types only)");
        }
        d = d.replaceFirst("^[<>|=]", "");
        if (hasSamples) {
            if (!d.equals("u1")) {
                throw new IllegalArgumentException("A sample (RGB) axis requires uint8, not \"" + dtype + "\"");
            }
            return Kind.RGB;
        }
        switch (d) {
            case "u1": return Kind.GRAY8;
            case "u2": return Kind.GRAY16;
            default: return Kind.GRAY32;
        }
    }

    @Override public int getWidth() { return w; }
    @Override public int getHeight() { return h; }
    @Override public int getSizeC() { return sizeC; }
    @Override public int getSizeZ() { return sizeZ; }
    @Override public int getSizeT() { return sizeT; }
    @Override public Kind getKind() { return kind; }

    @Override
    public Object readPlane(int t, int c, int z) {
        long[] pos = new long[map.ndim];
        if (map.t >= 0) pos[map.t] = t;
        if (map.c >= 0) pos[map.c] = c;
        if (map.z >= 0) pos[map.z] = z;
        if (kind != Kind.RGB) return copy(slice(pos));
        int[] out = new int[w * h];
        for (int s = 0; s < 3; s++) {
            pos[map.s] = s;
            byte[] b = (byte[]) copy(slice(pos), Kind.GRAY8);
            int shift = 16 - 8 * s;
            for (int i = 0; i < out.length; i++) out[i] |= (b[i] & 0xff) << shift;
        }
        return out;
    }

    /** 2D view of the plane; its two dimensions are x and y in descriptor order. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private RandomAccessibleInterval<? extends RealType<?>> slice(long[] pos) {
        RandomAccessibleInterval v = rai;
        for (int d = map.ndim - 1; d >= 0; d--) { // high to low keeps lower indices valid
            if (d != map.x && d != map.y) v = Views.hyperSlice(v, d, pos[d]);
        }
        return v;
    }

    private Object copy(RandomAccessibleInterval<? extends RealType<?>> v) { return copy(v, kind); }

    /**
     * Copies a 2D slice into a row-major array. Iterates in the view's own (memory-friendly) order
     * and scatters by position; ~20% faster than a permuted flat cursor on an 8192x8192 plane.
     */
    private Object copy(RandomAccessibleInterval<? extends RealType<?>> v, Kind k) {
        Cursor<? extends RealType<?>> cur = Views.iterable(v).localizingCursor();
        final boolean xFirst = map.x < map.y;
        final int px = xFirst ? 0 : 1, py = xFirst ? 1 : 0;
        switch (k) {
            case GRAY8: {
                byte[] a = new byte[w * h];
                while (cur.hasNext()) {
                    RealType<?> t = cur.next();
                    a[cur.getIntPosition(py) * w + cur.getIntPosition(px)] = (byte) ((IntegerType<?>) t).getInteger();
                }
                return a;
            }
            case GRAY16: {
                short[] a = new short[w * h];
                while (cur.hasNext()) {
                    RealType<?> t = cur.next();
                    a[cur.getIntPosition(py) * w + cur.getIntPosition(px)] = (short) ((IntegerType<?>) t).getInteger();
                }
                return a;
            }
            default: {
                float[] a = new float[w * h];
                while (cur.hasNext()) {
                    float f = cur.next().getRealFloat();
                    a[cur.getIntPosition(py) * w + cur.getIntPosition(px)] = f;
                }
                return a;
            }
        }
    }
}
