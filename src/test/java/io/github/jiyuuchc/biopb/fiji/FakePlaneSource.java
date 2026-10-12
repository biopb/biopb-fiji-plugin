package io.github.jiyuuchc.biopb.fiji;

import java.util.concurrent.atomic.AtomicInteger;

/** Fake source: GRAY16 plane whose pixel (x,y) = t*1000 + c*100 + z*10 + (x % 10); counts reads. */
class FakePlaneSource implements PlaneSource {
    final int w, h, c, z, t;
    final Kind kind;
    final AtomicInteger reads = new AtomicInteger();

    FakePlaneSource(int w, int h, int c, int z, int t, Kind kind) {
        this.w = w; this.h = h; this.c = c; this.z = z; this.t = t; this.kind = kind;
    }

    FakePlaneSource(int c, int z, int t) { this(6, 4, c, z, t, Kind.GRAY16); }

    @Override public int getWidth() { return w; }
    @Override public int getHeight() { return h; }
    @Override public int getSizeC() { return c; }
    @Override public int getSizeZ() { return z; }
    @Override public int getSizeT() { return t; }
    @Override public Kind getKind() { return kind; }

    @Override
    public Object readPlane(int ti, int ci, int zi) {
        reads.incrementAndGet();
        int n = w * h;
        switch (kind) {
            case GRAY8: { byte[] a = new byte[n]; java.util.Arrays.fill(a, (byte) (ti * 100 + ci * 10 + zi)); return a; }
            case GRAY32: { float[] a = new float[n]; java.util.Arrays.fill(a, ti * 1000 + ci * 100 + zi * 10 + 0.5f); return a; }
            case RGB: { int[] a = new int[n]; java.util.Arrays.fill(a, 0x010203); return a; }
            default: {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) a[i] = (short) (ti * 1000 + ci * 100 + zi * 10 + (i % w) % 10);
                return a;
            }
        }
    }
}
