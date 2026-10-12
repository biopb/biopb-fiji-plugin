package io.github.jiyuuchc.biopb.fiji;

import ij.VirtualStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A lazy {@link ij.ImageStack} whose planes are read on demand from a {@link PlaneSource}.
 *
 * <p>Stack index {@code n} (1-based, ImageJ's order) maps to {@code (c, z, t)} as
 * {@code n-1 = c + C*(z + Z*t)}; the stack decodes that and asks the source for the plane, so
 * nothing is reordered. Reads are thread-safe; a small LRU cache keeps the last few planes (each
 * caller gets its own copy of the pixels, so in-place edits never corrupt the cache). Concurrent
 * requests for the same missing plane share one read.
 *
 * <p>Planes are copied out of the SDK's lazy image with a cursor (the SDK has no bulk array
 * access), synchronously on whichever thread calls {@link #getProcessor}; the first call for a
 * plane can therefore take as long as a server fetch plus a per-pixel copy.
 *
 * <p>The stack is read-only: methods that would add, delete or replace planes throw
 * {@link UnsupportedOperationException}.
 */
public class TensorVirtualStack extends VirtualStack {
    /** Default number of planes kept in the LRU cache. */
    public static final int DEFAULT_CACHE_PLANES = 4;
    /** Copies of a plane alive at once while serving it: cache entry, returned clone, temporary read. */
    private static final int WORKING_COPIES = 3;
    private static final double HEAP_FRACTION = 0.6;
    private static final long MAX_CACHE_BYTES = 256L << 20;
    private static final int SAMPLE_TARGET = 1 << 16;

    private final PlaneSource source;
    private final int sizeC, sizeZ, sizeT;
    private final int cacheSize;
    private final Map<Integer, Object> cache = new LinkedHashMap<>(8, 0.75f, true);
    private final Map<Integer, CompletableFuture<Object>> inFlight = new HashMap<>(); // guarded by cache
    private final double[][] ranges; // per channel, lazily filled; entries are immutable once set

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
        long planeBytes = (long) source.getWidth() * source.getHeight() * source.getKind().bytesPerPixel();
        this.ranges = new double[sizeC][];
        long maxPx = maxPlanePixels(Long.MAX_VALUE, source.getKind().bytesPerPixel());
        if ((long) source.getWidth() * source.getHeight() > maxPx) {
            throw new PlaneTooLargeException("A " + source.getWidth() + " x " + source.getHeight() + " plane needs about "
                    + (planeBytes * WORKING_COPIES >> 20) + " MB of working memory but only " + (Runtime.getRuntime().maxMemory() >> 20)
                    + " MB heap is available; use a coarser pyramid level or give Fiji more memory",
                    (long) source.getWidth() * source.getHeight(), maxPx);
        }
        this.cacheSize = (int) Math.max(1, Math.min(cachePlanes, MAX_CACHE_BYTES / Math.max(1, planeBytes)));
    }

    /**
     * Largest plane (in pixels) that can be served: the smaller of {@code limit}, ImageJ's array
     * limit, and what fits in 60% of the heap with 3 copies of the plane alive (cache entry, returned clone, temporary read).
     *
     * @param bytesPerPixel size of one pixel of the returned array (see {@link PlaneSource.Kind#bytesPerPixel})
     */
    public static long maxPlanePixels(long limit, int bytesPerPixel) {
        long heap = (long) (Runtime.getRuntime().maxMemory() * HEAP_FRACTION / (WORKING_COPIES * (double) bytesPerPixel));
        return Math.min(Math.min(limit, Integer.MAX_VALUE), heap);
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

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("read-only server tensor");
    }

    @Override public void addSlice(String sliceLabel) { throw readOnly(); }
    @Override public void addSlice(String sliceLabel, Object pixels) { throw readOnly(); }
    @Override public void addSlice(ImageProcessor ip) { throw readOnly(); }
    @Override public void addSlice(String sliceLabel, ImageProcessor ip) { throw readOnly(); }
    @Override public void addSlice(String sliceLabel, ImageProcessor ip, int n) { throw readOnly(); }
    @Override public void deleteSlice(int n) { throw readOnly(); }
    @Override public void deleteLastSlice() { throw readOnly(); }
    @Override public void setProcessor(ImageProcessor ip, int n) { throw readOnly(); }
    @Override public void reduce(int factor) { throw readOnly(); }
    @Override public void setIndexes(int[] indexes) { throw readOnly(); }

    @Override public Object[] getImageArray() { return null; }

    /**
     * Deliberately a no-op: ImageJ itself calls this when it swaps the displayed processor, so
     * throwing would break display. Edits to a plane are discarded, never written back.
     */
    @Override public void setPixels(Object pixels, int n) { }

    /** No-op: labels are derived from the plane position. */
    @Override public void setSliceLabel(String label, int n) { }

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
        double[] r = getDisplayRange((n - 1) % sizeC);
        ip.setMinAndMax(r[0], r[1]);
        return ip;
    }

    /** Display range of channel 0; see {@link #getDisplayRange(int)}. */
    public double[] getDisplayRange() { return getDisplayRange(0); }

    /**
     * A cheap display range for a channel (0-based): 0-255 for 8-bit and RGB, otherwise the
     * min/max of a strided sample (about 64k pixels) of that channel's first plane, computed once
     * and outside any lock. A constant or empty plane falls back to the dtype range (0-65535 for
     * 16-bit; value to value+1 for float). Never scans a full plane.
     *
     * @return {@code {min, max}}
     */
    public double[] getDisplayRange(int channel) {
        double[] r = ranges[channel];
        if (r == null) {
            switch (source.getKind()) {
                case GRAY8: case RGB: r = new double[] {0, 255}; break;
                default: r = sampleRange(getPlane(channel), source.getKind(), source.getWidth(), source.getHeight());
            }
            ranges[channel] = r; // benign race: racing threads compute equal values
        }
        return r.clone();
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
        if (min > max) { // empty (all NaN)
            return kind == PlaneSource.Kind.GRAY16 ? new double[] {0, 65535} : new double[] {0, 1};
        }
        if (min == max) {
            return kind == PlaneSource.Kind.GRAY16 ? new double[] {0, 65535} : new double[] {min, min + 1};
        }
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
        CompletableFuture<Object> mine = null, theirs;
        synchronized (cache) {
            Object hit = cache.get(key);
            if (hit != null) return hit;
            theirs = inFlight.get(key);
            if (theirs == null) {
                mine = new CompletableFuture<>();
                inFlight.put(key, mine);
            }
        }
        if (mine == null) { // someone else is reading this plane; share their result
            try {
                return theirs.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : e;
            }
        }
        try {
            int c = idx % sizeC;
            int z = idx / sizeC % sizeZ;
            int t = idx / (sizeC * sizeZ);
            Object px = source.readPlane(t, c, z);
            synchronized (cache) {
                cache.put(key, px);
                if (cache.size() > cacheSize) {
                    cache.remove(cache.keySet().iterator().next());
                }
                inFlight.remove(key);
            }
            mine.complete(px);
            return px;
        } catch (RuntimeException | Error e) {
            synchronized (cache) { inFlight.remove(key); }
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private void checkRange(int n) {
        if (n < 1 || n > getSize()) {
            throw new IllegalArgumentException("Argument out of range: " + n);
        }
    }
}
