package io.github.jiyuuchc.biopb.fiji;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/**
 * The few calls {@link SourceList} makes of a tensor server, with plain Java
 * types so the model can be tested without a server or Arrow.
 * {@link FlightCatalogClient} is the real implementation.
 */
public interface CatalogClient {

    /** One folder of the catalog tree. */
    final class Level {
        private final String key;
        private final List<Folder> folders;
        private final List<Source> sources;
        private final boolean truncated;

        /**
         * @param key the folder's key ({@code ""} is the top)
         * @param folders the sub-folders
         * @param sources the sources directly in it
         * @param truncated whether the limit cut the level
         */
        public Level(String key, List<Folder> folders, List<Source> sources, boolean truncated) {
            this.key = key;
            // A level without its lists is malformed (the Python's _valid_level).
            this.folders = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(folders, "folders")));
            this.sources = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(sources, "sources")));
            this.truncated = truncated;
        }

        /** The folder this level lists. */
        public String getKey() { return key; }
        /** The sub-folders. */
        public List<Folder> getFolders() { return folders; }
        /** The sources directly in the folder. */
        public List<Source> getSources() { return sources; }
        /** Whether the limit cut the level, so the folder holds more than is listed. */
        public boolean isTruncated() { return truncated; }
    }

    /** A sub-folder: display name and the key that opens it. */
    final class Folder {
        private final String name;
        private final String key;

        public Folder(String name, String key) { this.name = name; this.key = key; }

        public String getName() { return name; }
        public String getKey() { return key; }
    }

    /** A source in a folder: display name and id. */
    final class Source {
        private final String name;
        private final String sourceId;

        public Source(String name, String sourceId) { this.name = name; this.sourceId = sourceId; }

        public String getName() { return name; }
        public String getSourceId() { return sourceId; }
    }

    /** A server heartbeat while a source resolves. */
    final class ResolveProgress {
        private final double elapsedSeconds;
        private final String targetName;
        private final long targetBytes;

        /**
         * @param elapsedSeconds seconds since the server began resolving
         * @param targetName the file being recalled, or empty if the server cannot name it
         * @param targetBytes its size, or 0 if unknown
         */
        public ResolveProgress(double elapsedSeconds, String targetName, long targetBytes) {
            this.elapsedSeconds = elapsedSeconds;
            this.targetName = targetName == null ? "" : targetName;
            this.targetBytes = targetBytes;
        }

        /** Seconds since the server began resolving. */
        public double getElapsedSeconds() { return elapsedSeconds; }
        /** The recall target's name, or empty. */
        public String getTargetName() { return targetName; }
        /** The recall target's size in bytes, or 0 if unknown. */
        public long getTargetBytes() { return targetBytes; }
    }

    /**
     * Reads one level of the catalog tree.
     *
     * @return the level, or {@code null} if the answer is malformed
     * @throws UnsupportedOperationException if the server (or SDK) predates the call
     * @throws IOException if the call fails
     */
    Level catalogChildren(String key, int limit) throws IOException;

    /** Runs SQL against the catalog; each row is a column-name to value map. */
    List<Map<String, Object>> query(String sql) throws IOException;

    /** The server's health map ({@code source_count}, {@code catalog_generation}, ...). */
    Map<String, Object> healthCheck() throws IOException;

    /**
     * The columns to select a source with, or {@code null} to use
     * {@link SourceList#BASE_COLUMNS}.
     */
    String sourceRowColumns();

    /**
     * Resolves an unresolved source (for a cloud placeholder this downloads the whole file, so it
     * can take minutes) and returns its terminal catalog row. Blocking: call off the UI thread.
     *
     * @param onProgress receives the server's heartbeats, or {@code null}
     * @param shouldCancel polled at each heartbeat; true stops the call, or {@code null}
     * @return the source's {@code sources} row, as {@link #query} rows are
     * @throws ResolveCancelledException if {@code shouldCancel} stopped the call
     * @throws UnsupportedOperationException if this client cannot resolve
     * @throws IOException if the call fails
     */
    default Map<String, Object> resolveSource(String sourceId, Consumer<ResolveProgress> onProgress,
            BooleanSupplier shouldCancel) throws IOException {
        throw new UnsupportedOperationException("resolve is not supported by this client");
    }

    /**
     * A source's free-form metadata (what the format carries beyond the tensors). Blocking: call off
     * the UI thread.
     *
     * @return the metadata, nested maps and lists of plain values; possibly empty
     * @throws UnsupportedOperationException if this client cannot read it
     * @throws IOException if the call fails
     */
    default Map<String, Object> getSourceMetadata(String sourceId) throws IOException {
        throw new UnsupportedOperationException("source metadata is not supported by this client");
    }
}
