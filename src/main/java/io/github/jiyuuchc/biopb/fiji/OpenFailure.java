package io.github.jiyuuchc.biopb.fiji;

import java.util.concurrent.ExecutionException;

/** Decides how a failed open is presented to the user, by exception type. No UI, so it is unit-testable. */
public final class OpenFailure {
    /** What to do about a failure. */
    public enum Kind {
        /** A plane is too large; offer the suggested coarser level (if any). */
        TOO_LARGE,
        /** The axes need an override from the user (none given yet). */
        NEEDS_AXES,
        /** The override the user gave was wrong; say so and ask again. */
        INVALID_AXES,
        /** Anything else: show the cause. */
        OTHER
    }

    /** The classified failure. */
    public final Kind kind;
    /** Suggested coarser level for {@link Kind#TOO_LARGE}, or -1 when none. */
    public final int suggestedLevel;
    /** Text for the dialog. */
    public final String message;

    private OpenFailure(Kind kind, int suggestedLevel, String message) {
        this.kind = kind;
        this.suggestedLevel = suggestedLevel;
        this.message = message;
    }

    /** Classifies {@code t}, unwrapping an ExecutionException wrapper. */
    public static OpenFailure classify(Throwable t) {
        while (t instanceof ExecutionException && t.getCause() != null) {
            t = t.getCause();
        }
        String msg = describe(t);
        if (t instanceof PlaneTooLargeException) {
            return new OpenFailure(Kind.TOO_LARGE, ((PlaneTooLargeException) t).getSuggestedLevel(), msg);
        }
        if (t instanceof AxisMappingException) {
            switch (((AxisMappingException) t).getReason()) {
                case NEEDS_OVERRIDE: return new OpenFailure(Kind.NEEDS_AXES, -1, msg);
                case INVALID_OVERRIDE: return new OpenFailure(Kind.INVALID_AXES, -1, msg);
                default: break;
            }
        }
        return new OpenFailure(Kind.OTHER, -1, msg);
    }

    /** The message of {@code t}, or its class name when it has none. */
    public static String describe(Throwable t) {
        return t.getMessage() != null && !t.getMessage().isEmpty() ? t.getMessage() : t.getClass().getName();
    }
}
