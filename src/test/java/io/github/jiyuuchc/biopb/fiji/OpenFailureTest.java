package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.ExecutionException;
import org.junit.Test;

public class OpenFailureTest {
    @Test
    public void tooLargeWithHintSuggestsThatLevel() {
        OpenFailure f = OpenFailure.classify(new ExecutionException(
                new PlaneTooLargeException("A plane ...; try pyramid level 3", 10, 5)));
        assertEquals(OpenFailure.Kind.TOO_LARGE, f.kind);
        assertEquals(3, f.suggestedLevel);
    }

    @Test
    public void tooLargeWithoutHint() {
        OpenFailure f = OpenFailure.classify(new PlaneTooLargeException("no coarser pyramid level is available", 10, 5));
        assertEquals(OpenFailure.Kind.TOO_LARGE, f.kind);
        assertEquals(-1, f.suggestedLevel);
    }

    @Test
    public void realAxisMapErrorsAskForOverride() {
        try {
            AxisMap.resolve(java.util.Collections.<String>emptyList(), new long[] {3, 4, 5, 6}, null);
        } catch (IllegalArgumentException e) {
            assertEquals(OpenFailure.Kind.NEEDS_AXES, OpenFailure.classify(e).kind);
            return;
        }
        throw new AssertionError("expected AxisMap to refuse");
    }

    @Test
    public void badOverrideAsksAgain() {
        assertEquals(OpenFailure.Kind.NEEDS_AXES,
                OpenFailure.classify(new IllegalArgumentException("Unknown axis role \"q\"; use one of t c z y x s -")).kind);
    }

    @Test
    public void otherErrorsAreShown() {
        assertEquals(OpenFailure.Kind.OTHER, OpenFailure.classify(new IllegalArgumentException("Level 9 not available")).kind);
        OpenFailure f = OpenFailure.classify(new RuntimeException());
        assertEquals(OpenFailure.Kind.OTHER, f.kind);
        assertEquals("java.lang.RuntimeException", f.message);
    }
}
