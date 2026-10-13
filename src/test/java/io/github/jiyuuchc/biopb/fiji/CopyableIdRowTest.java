package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The Copy button copies the value the row was given, and confirms it. Works headless. */
public class CopyableIdRowTest {
    @Test
    public void copiesTheGivenValueNotTheRenderedText() {
        CopyableIdRow row = new CopyableIdRow("Tensor");
        List<String> copied = new ArrayList<>();
        List<String> told = new ArrayList<>();
        row.setCopier(copied::add);
        row.setOnCopied(told::add);
        row.setValue("src/with spaces/0");
        row.button().doClick();
        assertEquals(java.util.Arrays.asList("src/with spaces/0"), copied);
        assertEquals(copied, told);
        assertEquals("src/with spaces/0", row.value());
    }

    @Test
    public void aFailingClipboardIsNotConfirmed() {
        CopyableIdRow row = new CopyableIdRow("Source");
        List<String> told = new ArrayList<>();
        row.setCopier(v -> { throw new IllegalStateException("busy"); });
        row.setOnCopied(told::add);
        row.setValue("x");
        row.button().doClick();
        assertTrue(told.isEmpty());
    }
}
