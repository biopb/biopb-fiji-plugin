package io.github.jiyuuchc.biopb.fiji;

/**
 * A source of 2D image planes addressed by (t, c, z). Implementations hide where the pixels come
 * from (a tensor server, or a fake in tests) and how the underlying axes are laid out; the stack
 * on top of it only ever asks for whole planes.
 *
 * <p>Implementations must be safe for concurrent {@link #readPlane} calls.
 */
public interface PlaneSource {

    /** The pixel format of the arrays returned by {@link #readPlane}. */
    enum Kind {
        /** {@code byte[]}, unsigned 8-bit. */
        GRAY8,
        /** {@code short[]}, unsigned 16-bit. */
        GRAY16,
        /** {@code float[]}, 32-bit float (other numeric types are converted to this). */
        GRAY32,
        /** {@code int[]}, packed 0xRRGGBB. */
        RGB
    }

    /** Plane width in pixels (x). */
    int getWidth();

    /** Plane height in pixels (y). */
    int getHeight();

    /** Number of channels (&gt;= 1). */
    int getSizeC();

    /** Number of z slices (&gt;= 1). */
    int getSizeZ();

    /** Number of time points (&gt;= 1). */
    int getSizeT();

    /** The pixel format of returned planes. */
    Kind getKind();

    /**
     * Reads one plane (zero-based indices) as a fresh, row-major (x fastest) primitive array of
     * {@code width*height} elements whose type is given by {@link #getKind()}. The caller owns it.
     */
    Object readPlane(int t, int c, int z);
}
