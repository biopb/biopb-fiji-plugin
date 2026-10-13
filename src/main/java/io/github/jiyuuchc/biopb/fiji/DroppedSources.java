package io.github.jiyuuchc.biopb.fiji;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which top-level rows of the tree were added by the user (dropped or picked) and so can be removed.
 * The server registers them under a {@code dnd://name} root, and only such a root can be deregistered.
 */
public final class DroppedSources {
    private DroppedSources() { }

    /** The URL prefix of a source added by drop. */
    public static final String PREFIX = "dnd://";

    private static final Pattern SEPARATORS = Pattern.compile("[\\\\/]");

    /** {@code dnd://name} for a source URL {@code dnd://name/...}, else {@code null}. */
    public static String rootOf(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            return null;
        }
        String first = SEPARATORS.split(url.substring(PREFIX.length()), 2)[0];
        return first.isEmpty() ? null : PREFIX + first;
    }

    /** The roots of the sources among {@code descriptors} (rows with {@code source_url}) that were added by drop. */
    public static Set<String> rootsOf(Collection<Map<String, Object>> descriptors) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> d : descriptors) {
            Object url = d.get("source_url");
            String root = url instanceof String ? rootOf((String) url) : null;
            if (root != null) {
                out.add(root);
            }
        }
        return out;
    }

    /**
     * The root to remove for a top-level folder, or {@code null}. Matched by name: a plain folder of
     * the same name must not offer a removal that would find no such drop.
     */
    public static String removeRootForFolder(String folderKey, Set<String> roots) {
        if (folderKey == null) {
            return null;
        }
        String first = SEPARATORS.split(folderKey, 2)[0];
        String root = PREFIX + first;
        return !first.isEmpty() && roots.contains(root) ? root : null;
    }

    /** The root to remove for a top-level source whose own URL is the root (a single dropped file), or {@code null}. */
    public static String removeRootForSource(String sourceUrl) {
        String root = rootOf(sourceUrl);
        return root != null && root.equals(sourceUrl) ? root : null;
    }
}
