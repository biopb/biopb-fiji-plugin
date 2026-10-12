package io.github.jiyuuchc.biopb.fiji;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
            this.folders = Collections.unmodifiableList(new ArrayList<>(folders));
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
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

    /**
     * Reads one level of the catalog tree.
     *
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
}
