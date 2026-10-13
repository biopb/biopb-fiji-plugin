package io.github.jiyuuchc.biopb.fiji;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plain-Java answers of the server's add-source and remove-source calls, so the
 * browser can be tested without the SDK's protobuf types.
 * {@link FlightCatalogClient} fills them from the SDK's results.
 */
public final class SourceChanges {
    private SourceChanges() { }

    /** One path the server could not add or remove, with its reason. */
    public static final class Failure {
        private final String path;
        private final String reason;

        public Failure(String path, String reason) {
            this.path = path == null ? "" : path;
            this.reason = reason == null ? "" : reason;
        }

        /** The path (an added item) or source id (a removed one) that failed. */
        public String getPath() { return path; }
        /** Why. */
        public String getReason() { return reason; }
    }

    /** A heartbeat of a running add: sources registered so far and what is being scanned. */
    public static final class AddProgress {
        private final int addedCount;
        private final String currentPath;

        public AddProgress(int addedCount, String currentPath) {
            this.addedCount = addedCount;
            this.currentPath = currentPath == null ? "" : currentPath;
        }

        /** Sources registered so far. */
        public int getAddedCount() { return addedCount; }
        /** A path being scanned, or a status sentence (the server's wait-for-lock heartbeat); may be empty. */
        public String getCurrentPath() { return currentPath; }
    }

    /** The terminal tally of one add. */
    public static final class AddResult {
        private final List<String> added;
        private final List<String> refreshed;
        private final List<String> removed;
        private final List<Failure> failed;
        private final int skippedOffline;
        private final int skippedCloudDirs;

        /**
         * @param added ids of the sources newly registered
         * @param refreshed ids of known sources that were rebuilt
         * @param removed ids of sources dropped because their files were gone
         * @param failed what could not be added
         * @param skippedOffline offline placeholder files passed over (cloud mode off)
         * @param skippedCloudDirs OneDrive directories passed over (cloud mode off)
         */
        public AddResult(List<String> added, List<String> refreshed, List<String> removed, List<Failure> failed,
                int skippedOffline, int skippedCloudDirs) {
            this.added = copy(added);
            this.refreshed = copy(refreshed);
            this.removed = copy(removed);
            this.failed = copy(failed);
            this.skippedOffline = skippedOffline;
            this.skippedCloudDirs = skippedCloudDirs;
        }

        public List<String> getAdded() { return added; }
        public List<String> getRefreshed() { return refreshed; }
        public List<String> getRemoved() { return removed; }
        public List<Failure> getFailed() { return failed; }
        public int getSkippedOffline() { return skippedOffline; }
        public int getSkippedCloudDirs() { return skippedCloudDirs; }

        /** Whether the catalog changed, so it is worth re-reading. */
        public boolean changedCatalog() {
            return !added.isEmpty() || !refreshed.isEmpty() || !removed.isEmpty();
        }
    }

    /** The terminal tally of one removal. */
    public static final class RemoveResult {
        private final List<String> removed;
        private final List<Failure> failed;

        public RemoveResult(List<String> removed, List<Failure> failed) {
            this.removed = copy(removed);
            this.failed = copy(failed);
        }

        /** Ids of the sources unregistered. */
        public List<String> getRemoved() { return removed; }
        public List<Failure> getFailed() { return failed; }
    }

    private static <T> List<T> copy(List<T> in) {
        return in == null ? Collections.<T>emptyList() : Collections.unmodifiableList(new ArrayList<>(in));
    }
}
