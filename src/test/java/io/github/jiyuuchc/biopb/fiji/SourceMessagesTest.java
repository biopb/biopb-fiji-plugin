package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The wording of add and remove reports. */
public class SourceMessagesTest {
    private static final List<String> NONE = Collections.emptyList();
    private static final List<SourceChanges.Failure> NO_FAILURES = Collections.emptyList();

    @Test
    public void progressNamesAScannedPathByBaseName() {
        assertEquals("Adding\u2026 1 source registered (scanning b)",
                SourceMessages.progress(new SourceChanges.AddProgress(1, "/a/b/")));
        assertEquals("Adding\u2026 2 sources registered (scanning b)",
                SourceMessages.progress(new SourceChanges.AddProgress(2, "C:\\a\\b")));
    }

    @Test
    public void progressShowsAStatusSentenceVerbatim() {
        assertEquals("Adding\u2026 0 sources registered (waiting for the catalog)",
                SourceMessages.progress(new SourceChanges.AddProgress(0, "waiting for the catalog")));
        assertEquals("Adding\u2026 3 sources registered", SourceMessages.progress(new SourceChanges.AddProgress(3, "")));
    }

    @Test
    public void addSummary() {
        List<SourceChanges.Failure> failed = Arrays.asList(new SourceChanges.Failure("/p/x", "bad"));
        assertEquals("Add data: added 2, 1 refreshed, 1 removed, 1 failed", SourceMessages.addSummary(
                new SourceChanges.AddResult(Arrays.asList("a", "b"), Arrays.asList("c"), Arrays.asList("d"), failed, 0, 0)));
        assertEquals("Add data: nothing",
                SourceMessages.addSummary(new SourceChanges.AddResult(NONE, NONE, NONE, NO_FAILURES, 0, 0)));
    }

    @Test
    public void failuresAreBulletedByBaseName() {
        assertEquals("\u2022 x: bad\n\u2022 y: worse", SourceMessages.addFailures(Arrays.asList(
                new SourceChanges.Failure("/p/x/", "bad"), new SourceChanges.Failure("/p/y", "worse"))));
        assertEquals("\u2022 s1: gone", SourceMessages.removeFailures(Arrays.asList(new SourceChanges.Failure("s1", "gone"))));
    }

    @Test
    public void skippedNamesWhatWasLeftOut() {
        String m = SourceMessages.skipped("/d/scans", 1, 2);
        assertTrue(m.startsWith("1 offline file (their contents are not on this PC) and 2 OneDrive folders in "
                + "\u201cscans\u201d were left out."));
        assertTrue(SourceMessages.skipped("/d/scans", 0, 1).startsWith("1 OneDrive folder in"));
    }

    @Test
    public void removeSummary() {
        assertEquals("Removed 1 source", SourceMessages.removeSummary(1));
        assertEquals("Removed 3 sources", SourceMessages.removeSummary(3));
        assertEquals("Nothing to remove", SourceMessages.removeSummary(0));
    }

    @Test
    public void changedCatalogIgnoresFailuresAndSkips() {
        assertTrue(new SourceChanges.AddResult(Arrays.asList("a"), NONE, NONE, NO_FAILURES, 0, 0).changedCatalog());
        assertTrue(!new SourceChanges.AddResult(NONE, NONE, NONE, NO_FAILURES, 4, 1).changedCatalog());
    }
}
