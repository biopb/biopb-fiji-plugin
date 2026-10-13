package io.github.jiyuuchc.biopb.fiji;

/**
 * Presents a {@link PlaneSource} with larger c/z/t sizes by repeating its singleton axes, so a label
 * set has the plane grid of its image (napari's {@code align_label_levels} broadcast). An axis that
 * is longer than 1 in the source must already match the requested size. The last plane read is
 * kept, so a mask repeated over many channels costs one read.
 */
final class BroadcastPlaneSource implements PlaneSource {
    private final PlaneSource inner;
    private final int c, z, t;
    private int[] lastKey; // guarded by this
    private Object lastPlane;

    BroadcastPlaneSource(PlaneSource inner, int sizeC, int sizeZ, int sizeT) {
        check("c", inner.getSizeC(), sizeC);
        check("z", inner.getSizeZ(), sizeZ);
        check("t", inner.getSizeT(), sizeT);
        this.inner = inner;
        this.c = sizeC;
        this.z = sizeZ;
        this.t = sizeT;
    }

    private static void check(String axis, int have, int want) {
        if (have != want && have != 1) {
            throw new IllegalArgumentException("Cannot broadcast " + axis + " of length " + have + " to " + want);
        }
    }

    @Override public int getWidth() { return inner.getWidth(); }
    @Override public int getHeight() { return inner.getHeight(); }
    @Override public int getSizeC() { return c; }
    @Override public int getSizeZ() { return z; }
    @Override public int getSizeT() { return t; }
    @Override public Kind getKind() { return inner.getKind(); }

    @Override
    public Object readPlane(int ti, int ci, int zi) {
        int[] key = {inner.getSizeT() == 1 ? 0 : ti, inner.getSizeC() == 1 ? 0 : ci, inner.getSizeZ() == 1 ? 0 : zi};
        if (key[0] == ti && key[1] == ci && key[2] == zi) {
            return inner.readPlane(ti, ci, zi); // nothing repeated: pass through, no copy
        }
        synchronized (this) { // a short lock: the remote read below is not under it
            if (lastKey != null && java.util.Arrays.equals(lastKey, key)) {
                return clone(lastPlane);
            }
        }
        Object plane = inner.readPlane(key[0], key[1], key[2]);
        Object mine = clone(plane); // one copy to keep, the original goes to the caller
        synchronized (this) {
            lastKey = key;
            lastPlane = mine;
        }
        return plane;
    }

    private static Object clone(Object plane) { // the caller owns what it gets
        if (plane instanceof byte[]) return ((byte[]) plane).clone();
        if (plane instanceof short[]) return ((short[]) plane).clone();
        if (plane instanceof float[]) return ((float[]) plane).clone();
        return ((int[]) plane).clone();
    }
}
