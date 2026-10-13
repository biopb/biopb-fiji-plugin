package io.github.jiyuuchc.biopb.fiji;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

/**
 * The judgement calls made before a path is sent to the server: is it inside a cloud-synced folder,
 * and is it a folder big enough to be a mistake. Ported from the napari widget; no Swing, no server.
 */
public final class CloudDrop {
    private CloudDrop() { }

    /**
     * A dropped folder with more entries than this prompts a confirmation before the scan is sent (a
     * footgun-stopper for dropping a home or root folder by mistake). Coarse on purpose: it counts
     * filesystem entries, not resulting sources.
     */
    public static final int LARGE_ENTRY_THRESHOLD = 2000;

    private static final Pattern SEPARATORS = Pattern.compile("[\\\\/]+");

    /**
     * Whether {@code name} is a OneDrive root directory name ({@code OneDrive} or {@code OneDrive - Org}).
     * A small copy of the server's discovery skip: the server declines to walk these trees, which is
     * what makes a source added from inside one worth a heads-up.
     */
    public static boolean isOneDriveDirName(String name) {
        String low = name.toLowerCase(java.util.Locale.ROOT);
        return low.equals("onedrive") || low.startsWith("onedrive -") || low.startsWith("onedrive-");
    }

    /**
     * Warning text if {@code path} sits in a cloud-synced (OneDrive) folder, else {@code null}.
     * Name-based and best-effort: it reads the path's components only (never opens a file, so it
     * cannot itself trigger a cloud recall). Split on both separators so a Windows path survives.
     */
    public static String warning(String path) {
        boolean inside = false;
        for (String c : SEPARATORS.split(path)) {
            if (!c.isEmpty() && isOneDriveDirName(c)) {
                inside = true;
            }
        }
        if (!inside) {
            return null;
        }
        return "“" + displayName(path) + "” is inside OneDrive, a cloud-synced folder.\n\n"
                + "Cloud-synced source support is experimental.\n\n"
                + "It will be indexed while its files are downloaded to this PC, but if OneDrive Files "
                + "On-Demand later dehydrates them to free up space, reads can become slow or fail until "
                + "Windows re-downloads them. Marking the files “Always keep on this device” avoids that.\n\n"
                + "Add anyway?";
    }

    /** The last component of {@code path} (trailing separators ignored), or the path itself if it has none. */
    public static String displayName(String path) {
        String p = path;
        int end = p.length();
        while (end > 0 && (p.charAt(end - 1) == '/' || p.charAt(end - 1) == '\\')) {
            end--;
        }
        p = p.substring(0, end);
        int cut = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String name = p.substring(cut + 1);
        return name.isEmpty() ? path : name;
    }

    /** {@link #exceedsEntryThreshold(String, int)} at {@link #LARGE_ENTRY_THRESHOLD}. */
    public static boolean exceedsEntryThreshold(String path) {
        return exceedsEntryThreshold(path, LARGE_ENTRY_THRESHOLD);
    }

    /**
     * Whether {@code path} is a directory holding more than {@code threshold} entries (files and
     * folders, recursively; symlinks counted, not followed). A file is never large. Stops at the
     * threshold, so it stays cheap on an enormous tree. This is the only size gate on an add, so a
     * walk error (permission, a race) reads as "not large" and the add proceeds unconfirmed rather
     * than being blocked.
     */
    public static boolean exceedsEntryThreshold(String path, int threshold) {
        final Path root;
        try {
            root = Paths.get(path);
        } catch (RuntimeException bad) { // InvalidPathException
            return false;
        }
        if (!Files.isDirectory(root)) {
            return false;
        }
        final int[] count = {0};
        final boolean[] over = {false};
        FileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                return tally(!dir.equals(root));
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                return tally(true);
            }

            @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE; // like os.walk: an unreadable entry is skipped
            }

            private FileVisitResult tally(boolean counts) {
                if (counts && ++count[0] > threshold) {
                    over[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        };
        try {
            Files.walkFileTree(root, visitor);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return over[0];
    }
}
