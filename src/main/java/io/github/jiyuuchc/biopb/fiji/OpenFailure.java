package io.github.jiyuuchc.biopb.fiji;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides how a failed open is presented to the user. No UI, so it is unit-testable. */
public final class OpenFailure {
    /** What to do about a failure. */
    public enum Kind {
        /** A plane is too large; offer the suggested coarser level (if any). */
        TOO_LARGE,
        /** The axes could not be mapped; ask for an axis override and retry. */
        NEEDS_AXES,
        /** Anything else: show the cause. */
        OTHER
    }

    private static final Pattern LEVEL_HINT = Pattern.compile("try pyramid level (\\d+)");
    private static final String[] AXIS_MARKERS = {
        "unlabeled", "axis override", "unsupported axis", "unknown axis role", "assigned twice",
        "could not find both y and x", "is ignored but has size"
    };

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

    /** Classifies {@code t}, unwrapping an ExecutionException-style wrapper. */
    public static OpenFailure classify(Throwable t) {
        while (t instanceof java.util.concurrent.ExecutionException && t.getCause() != null) {
            t = t.getCause();
        }
        String msg = describe(t);
        if (t instanceof PlaneTooLargeException) {
            Matcher m = LEVEL_HINT.matcher(msg);
            return new OpenFailure(Kind.TOO_LARGE, m.find() ? Integer.parseInt(m.group(1)) : -1, msg);
        }
        if (t instanceof IllegalArgumentException && msg != null) {
            String low = msg.toLowerCase(Locale.ROOT);
            for (String k : AXIS_MARKERS) {
                if (low.contains(k)) {
                    return new OpenFailure(Kind.NEEDS_AXES, -1, msg);
                }
            }
        }
        return new OpenFailure(Kind.OTHER, -1, msg);
    }

    /** The message of {@code t}, or its class name when it has none. */
    public static String describe(Throwable t) {
        return t.getMessage() != null && !t.getMessage().isEmpty() ? t.getMessage() : t.getClass().getName();
    }
}
