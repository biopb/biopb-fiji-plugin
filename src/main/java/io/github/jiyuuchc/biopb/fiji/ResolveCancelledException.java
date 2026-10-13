package io.github.jiyuuchc.biopb.fiji;

/**
 * A resolve that the caller stopped (its {@code shouldCancel} returned true). Unchecked, like the
 * SDK's own cancellation, so it passes through {@link SourceList#resolve} untouched. The server
 * may finish its recall anyway and cache it, so a later resolve of the same source can be quick.
 */
public final class ResolveCancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @param sourceId the source whose resolve was stopped */
    public ResolveCancelledException(String sourceId) {
        super("Resolving '" + sourceId + "' was cancelled");
    }

    /** As {@link #ResolveCancelledException(String)}, with the SDK's exception as the cause. */
    public ResolveCancelledException(String sourceId, Throwable cause) {
        super("Resolving '" + sourceId + "' was cancelled", cause);
    }
}
