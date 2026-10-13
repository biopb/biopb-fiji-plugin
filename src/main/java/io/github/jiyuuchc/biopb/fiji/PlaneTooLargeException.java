package io.github.jiyuuchc.biopb.fiji;

/** A single plane exceeds the size that can (or should) be materialized; try a coarser pyramid level. */
public class PlaneTooLargeException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final long planePixels;
    private final long limit;
    private final int suggestedLevel;

    /**
     * @param message     explanation, ideally naming a coarser level
     * @param planePixels pixels in the offending plane
     * @param limit       the limit it exceeded
     */
    public PlaneTooLargeException(String message, long planePixels, long limit) {
        this(message, planePixels, limit, -1);
    }

    /** As above, naming the coarser level that fits ({@code -1} for none). */
    public PlaneTooLargeException(String message, long planePixels, long limit, int suggestedLevel) {
        super(message);
        this.suggestedLevel = suggestedLevel;
        this.planePixels = planePixels;
        this.limit = limit;
    }

    /** The coarser pyramid level that fits, or -1 if none does. */
    public int getSuggestedLevel() { return suggestedLevel; }

    /** Pixels in the plane that was refused. */
    public long getPlanePixels() { return planePixels; }

    /** The limit (in pixels per plane) that was exceeded. */
    public long getLimit() { return limit; }
}
