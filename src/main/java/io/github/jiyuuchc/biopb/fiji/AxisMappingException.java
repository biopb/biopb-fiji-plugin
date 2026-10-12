package io.github.jiyuuchc.biopb.fiji;

/** The tensor's axes could not be mapped to image dimensions; {@link #getReason()} says what the user can do. */
public class AxisMappingException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Why mapping failed. */
    public enum Reason {
        /** Unlabeled, unsupported or ambiguous axes: an axis override would fix it. */
        NEEDS_OVERRIDE,
        /** The axis override that was supplied is itself wrong. */
        INVALID_OVERRIDE,
        /** Not fixable by an override (size limits, unsupported layouts). */
        OTHER
    }

    private final Reason reason;

    /** @param reason why mapping failed @param message explanation for the user */
    public AxisMappingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** Why mapping failed. */
    public Reason getReason() { return reason; }
}
