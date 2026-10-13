package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import org.junit.Test;

public class OpenFailureTest {
    private static AxisMappingException.Reason reason(java.util.List<String> labels, long[] shape, String override) {
        try {
            AxisMap.resolve(labels, shape, override);
        } catch (AxisMappingException e) {
            return e.getReason();
        }
        throw new AssertionError("expected AxisMap to refuse");
    }

    @Test
    public void tooLargeCarriesSuggestedLevel() {
        OpenFailure f = OpenFailure.classify(new ExecutionException(new PlaneTooLargeException("big", 10, 5, 3)));
        assertEquals(OpenFailure.Kind.TOO_LARGE, f.kind);
        assertEquals(3, f.suggestedLevel);
        assertEquals(-1, new PlaneTooLargeException("big", 10, 5).getSuggestedLevel());
        assertEquals(-1, OpenFailure.classify(new PlaneTooLargeException("big", 10, 5)).suggestedLevel);
    }

    @Test
    public void axisMapReasons() {
        assertEquals(AxisMappingException.Reason.NEEDS_OVERRIDE,
                reason(Collections.<String>emptyList(), new long[] {3, 4, 5, 6}, null));
        assertEquals(AxisMappingException.Reason.NEEDS_OVERRIDE,
                reason(Arrays.asList("a", "b", "y", "x"), new long[] {3, 4, 5, 6}, null));
        assertEquals(AxisMappingException.Reason.INVALID_OVERRIDE,
                reason(Collections.<String>emptyList(), new long[] {3, 4, 5, 6}, "zyx"));
        assertEquals(AxisMappingException.Reason.INVALID_OVERRIDE,
                reason(Collections.<String>emptyList(), new long[] {3, 4, 5, 6}, "qzyx"));
        assertEquals(AxisMappingException.Reason.INVALID_OVERRIDE,
                reason(Collections.<String>emptyList(), new long[] {3, 4, 5, 6}, "zzyx"));
        assertEquals(AxisMappingException.Reason.OTHER,
                reason(Arrays.asList("y", "x", "s"), new long[] {5, 5, 4}, null));
    }

    @Test
    public void classifiesByType() {
        assertEquals(OpenFailure.Kind.NEEDS_AXES, OpenFailure.classify(
                new AxisMappingException(AxisMappingException.Reason.NEEDS_OVERRIDE, "m")).kind);
        assertEquals(OpenFailure.Kind.INVALID_AXES, OpenFailure.classify(
                new AxisMappingException(AxisMappingException.Reason.INVALID_OVERRIDE, "m")).kind);
        assertEquals(OpenFailure.Kind.OTHER, OpenFailure.classify(
                new AxisMappingException(AxisMappingException.Reason.OTHER, "m")).kind);
        // wording is irrelevant: a plain exception that mentions axes is just an error
        assertEquals(OpenFailure.Kind.OTHER, OpenFailure.classify(
                new IllegalArgumentException("unlabeled axis override")).kind);
        OpenFailure f = OpenFailure.classify(new RuntimeException());
        assertEquals("java.lang.RuntimeException", f.message);
    }
}
