package io.github.jiyuuchc.biopb.fiji;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The wording of adding and removing sources, as the napari widget words it. Plain text, no Swing. */
public final class SourceMessages {
    private SourceMessages() { }

    private static final Pattern ABSOLUTE = Pattern.compile("^(/|[A-Za-z]:[\\\\/]|\\\\\\\\)");

    /** The hint line while an add runs: sources registered so far and what is being scanned. */
    public static String progress(SourceChanges.AddProgress p) {
        int n = p.getAddedCount();
        String msg = "Adding… " + n + " source" + (n == 1 ? "" : "s") + " registered";
        String path = p.getCurrentPath();
        if (ABSOLUTE.matcher(path).find()) {
            msg += " (scanning " + CloudDrop.displayName(path) + ")"; // a real path: its base name
        } else if (!path.isEmpty()) {
            msg += " (" + path + ")"; // a status sentence (the lock-wait heartbeat): verbatim
        }
        return msg;
    }

    /** The status line after an add: {@code Add data: added 2, 1 refreshed, ...}, or {@code nothing}. */
    public static String addSummary(SourceChanges.AddResult r) {
        List<String> parts = new ArrayList<>();
        if (!r.getAdded().isEmpty()) {
            parts.add("added " + r.getAdded().size());
        }
        if (!r.getRefreshed().isEmpty()) {
            parts.add(r.getRefreshed().size() + " refreshed");
        }
        if (!r.getRemoved().isEmpty()) {
            parts.add(r.getRemoved().size() + " removed");
        }
        if (!r.getFailed().isEmpty()) {
            parts.add(r.getFailed().size() + " failed");
        }
        return "Add data: " + (parts.isEmpty() ? "nothing" : String.join(", ", parts));
    }

    /** One bullet per path the add could not take, by base name. */
    public static String addFailures(List<SourceChanges.Failure> failed) {
        List<String> lines = new ArrayList<>();
        for (SourceChanges.Failure f : failed) {
            lines.add("• " + CloudDrop.displayName(f.getPath()) + ": " + f.getReason());
        }
        return String.join("\n", lines);
    }

    /** What the add left out: offline files and OneDrive folders (everything inside the latter is missing, uncounted). */
    public static String skipped(String path, int files, int dirs) {
        List<String> parts = new ArrayList<>();
        if (files > 0) {
            parts.add(files + " offline file" + (files == 1 ? "" : "s") + " (their contents are not on this PC)");
        }
        if (dirs > 0) {
            parts.add(dirs + " OneDrive folder" + (dirs == 1 ? "" : "s"));
        }
        return String.join(" and ", parts) + " in “" + CloudDrop.displayName(path) + "” were left out.\n\n"
                + "To include them, remove the folder and drop it again.";
    }

    /** The question for a path whose last add left files out. */
    public static String cloudRedrop(String path) {
        return "Offline files or OneDrive folders in “" + CloudDrop.displayName(path) + "” were left out the "
                + "last time it was added.\n\nAdd it as a cloud folder this time, so they are included? "
                + "Opening one will download it first.";
    }

    /** The question before scanning a big folder. */
    public static String largeFolder(String path) {
        return "“" + CloudDrop.displayName(path) + "” contains many files. Scan it and add all datasets found under it?";
    }

    /** The question before removing a dropped source. */
    public static String confirmRemove(String displayName, String rootUrl) {
        return "Remove “" + displayName + "” from the browser?\n\n"
                + "This unregisters every source the server holds under " + rootUrl + " (added by drag-drop or "
                + "the Add button). Other sources, even in a folder of the same name, are not touched, and the "
                + "underlying files on disk are not deleted.";
    }

    /** The status line after a removal. */
    public static String removeSummary(int removed) {
        return removed > 0 ? "Removed " + removed + " source" + (removed == 1 ? "" : "s") : "Nothing to remove";
    }

    /** One bullet per source the removal could not take. */
    public static String removeFailures(List<SourceChanges.Failure> failed) {
        List<String> lines = new ArrayList<>();
        for (SourceChanges.Failure f : failed) {
            lines.add("• " + f.getPath() + ": " + f.getReason());
        }
        return String.join("\n", lines);
    }
}
