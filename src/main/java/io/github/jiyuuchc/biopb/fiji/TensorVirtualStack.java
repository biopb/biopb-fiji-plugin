package io.github.jiyuuchc.biopb.fiji;

import ij.VirtualStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A lazy {@link ij.ImageStack} whose planes are read on demand from a {@link PlaneSource}.
 *
 * <p>Stack index {@code n} (1-based, ImageJ's order) maps to {@code (c, z, t)} as
 * {@code n-1 = c + C*(z + Z*t)}; the stack decodes that and asks the source for the plane, so
 * nothing is reordered. Reads are thread-safe; a small LRU cache keeps the last few planes (each
 * caller gets its own copy of the pixels, so in-place edits never corrupt the cache).
 */
public class TensorVirtualStack extends VirtualStack {
    /** Default number of planes kept in the LRU cache. */
    public static final int DEFAULT_CACHE_PLANES = 4;
    private static final long MAX_CACHE_BYTES = 256L << 20;
    private static final int SAMPLE_TARGET = 1 << 16;

    private final PlaneSource source;
    private final int sizeC, sizeZ, sizeT;
    private final int cacheSize;
    private final Map<Integer, Object> cache = new LinkedHashMap<>(8, 0.75f, true);
    private double[] range; // guarded by this

    /** Creates a stack over {@code source} with the default cache size. */
    public TensorVirtualStack(PlaneSource source) {
        this(source, DEFAULT_CACHE_PLANES);
    }

    /**
     * @param source     where planes come from
     * @param cachePlanes number of planes to cache (at least 1); also capped so the cache holds
     *                    about 256 MB at most (but never fewer than one plane)
     */
    public TensorVirtualStack(PlaneSource source, int cachePlanes) {
        super(source.getWidth(), source.getHeight(), null, "");
        this.source = source;
        this.sizeC = source.getSizeC();
        this.sizeZ = source.getSizeZ();
        this.sizeT = source.getSizeT();
        long planeBytes = (long) source.getWidth() * source.getHeight() * (getBitDepth() / 8);
        this.cacheSize = (int) Math.max(1, Math.min(cachePlanes, MAX_CACHE_BYTES / Math.max(1, planeBytes)));
    }

    /** The underlying plane source. */
    public PlaneSource getSource() { return source; }

    @Override public int getSize() { return sizeC * sizeZ * sizeT; }

    @Override public int size() { return getSize(); }

    @Override public boolean isVirtual() { return true; }

    @Override
    public int getBitDepth() {
        switch (source.getKind()) {
            case GRAY8: return 8;
            case GRAY16: return 16;
            case GRAY32: return 32;
            default: return 24;
        }
    }

    @Override public void setBitDepth(int bitDepth) { /* fixed by the source */ }

    /** Label such as {@code "t=2 c=1 z=3"} (1-based; axes of size 1 omitted). */
    @Override
    public String getSliceLabel(int n) {
        checkRange(n);
        int i = n - 1;
        StringBuilder sb = new StringBuilder();
        if (sizeT > 1) sb.append("t=").append(i / (sizeC * sizeZ) + 1);
        if (sizeC > 1) sb.append(sb.length() > 0 ? " " : "").append("c=").append(i % sizeC + 1);
        if (sizeZ > 1) sb.append(sb.length() > 0 ? " " : "").append("z=").append(i / sizeC % sizeZ + 1);
        return sb.length() > 0 ? sb.toString() : "plane";
    }

    @Override public String getShortSliceLabel(int n) { return getSliceLabel(n); }

    @Override public String getFileName(int n) { return getSliceLabel(n); }

    @Override public String getDirectory() { return ""; }

    @Override public int saveChanges(int n) { return -1; }

    @Override public Object[] getImageArray() { return null; }

    @Override public void setPixels(Object pixels, int n) { /* read-only */ }

    @Override public void setSliceLabel(String label, int n) { /* labels are derived */ }

    @Override public Object getPixels(int n) { return readCopy(n); }

    @Override
    public ImageProcessor getProcessor(int n) {
        Object px = readCopy(n);
        int w = source.getWidth(), h = source.getHeight();
        ImageProcessor ip;
        switch (source.getKind()) {
            case GRAY8: return new ByteProcessor(w, h, (byte[]) px, null);
            case GRAY16: ip = new ShortProcessor(w, h, (short[]) px, null); break;
            case GRAY32: ip = new FloatProcessor(w, h, (float[]) px, null); break;
            default: return new ColorProcessor(w, h, (int[]) px);
        }
        // Fixing the range up front stops ImageJ from scanning the whole plane for min/max.
        double[] r = getDisplayRange();
        ip.setMinAndMax(r[0], r[1]);
        return ip;
    }

    /**
     * A cheap display range: 0-255 for 8-bit, otherwise the min/max of a strided sample (about
     * 64k pixels) of the first plane. Computed once, never scans a full plane.
     *
     * @return {@code {min, max}}
     */
    public synchronized double[] getDisplayRange() {
        if (range == null) {
            switch (source.getKind()) {
                case GRAY8: case RGB: range = new double[] {0, 255}; break;
                default: range = sampleRange(getPlane(0), source.getKind(), source.getWidth(), source.getHeight());
            }
        }
        return range.clone();
    }

    static double[] sampleRange(Object px, PlaneSource.Kind kind, int w, int h) {
        long n = (long) w * h;
        // Odd stride so that sampling does not lock onto a single column when w is a multiple.
        int step = (int) Math.max(1, n / SAMPLE_TARGET) | 1;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (long i = 0; i < n; i += step) {
            double v = kind == PlaneSource.Kind.GRAY16 ? ((short[]) px)[(int) i] & 0xffff : ((float[]) px)[(int) i];
            if (Double.isNaN(v) || Double.isInfinite(v)) continue;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (min > max) { min = 0; max = 1; }
        if (min == max) max = min + 1;
        return new double[] {min, max};
    }

    private Object readCopy(int n) {
        checkRange(n);
        Object px = getPlane(n - 1);
        // Clone so ImageJ may modify the returned pixels; the cache keeps the original.
        if (px instanceof byte[]) return ((byte[]) px).clone();
        if (px instanceof short[]) return ((short[]) px).clone();
        if (px instanceof float[]) return ((float[]) px).clone();
        return ((int[]) px).clone();
    }

    private Object getPlane(int idx) {
        Integer key = idx;
        synchronized (cache) {
            Object hit = cache.get(key);
            if (hit != null) return hit;
        }
        int c = idx % sizeC;
        int z = idx / sizeC % sizeZ;
        int t = idx / (sizeC * sizeZ);
        Object px = source.readPlane(t, c, z); // outside the lock: concurrent reads may overlap
        synchronized (cache) {
            cache.put(key, px);
            if (cache.size() > cacheSize) {
                cache.remove(cache.keySet().iterator().next());
            }
        }
        return px;
    }

    private void checkRange(int n) {
        if (n < 1 || n > getSize()) {
            throw new IllegalArgumentException("Argument out of range: " + n);
        }
    }
}
