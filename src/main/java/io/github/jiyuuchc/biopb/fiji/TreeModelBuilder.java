package io.github.jiyuuchc.biopb.fiji;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Builds the Tensor Browser's tree models from {@link SourceList} snapshots. No
 * component is involved (the nodes are plain {@code javax.swing.tree} data), so
 * it runs, and is tested, without a display. Every user object is a {@link Node}.
 *
 * <p>A lazy catalog is drawn from its levels ({@link #levelChildren}): a closed
 * folder holds a "Loading..." placeholder so it can be opened, and opening reads it.
 * A whole listing, and a search result, is drawn from the sources' URLs
 * ({@link #buildPaths}).
 */
public final class TreeModelBuilder {
    /** Most matches a search asks for. */
    public static final int SEARCH_LIMIT = 2000;
    /** Shown under a folder the server holds more of than it sent. */
    public static final String CUT_NOTE = "Only part of this folder is shown - search to find the rest";
    /** Shown under a folder that is being read. */
    public static final String LOADING_NOTE = "Loading…";
    /** Shown under a folder whose read failed; opening it again retries. */
    public static final String FAILED_NOTE = "Could not load this folder";

    /** Shown on a cloud placeholder: resolving it downloads the file. */
    public static final String CLOUD_GLYPH = "\u2601";
    /** Shown on a source the server is still indexing. */
    public static final String PENDING_GLYPH = "\u22ef";
    /** Marks a label-set row (a filled ring: the row already carries the set's name and shape). */
    public static final String LABEL_GLYPH = "\u25c9";
    private static final String CLOUD_NOTE = "Cloud file, not downloaded. Resolving it downloads the whole file.";
    private static final String PENDING_NOTE = "Being indexed in the background.";

    private static final Pattern SEPARATORS = Pattern.compile("[\\\\/]+");
    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:");

    private TreeModelBuilder() { }

    /** What a tree row is. */
    public enum Kind { FOLDER, SOURCE, TENSOR, LABEL, NOTE }

    /**
     * Why a source is unresolved, as the server's {@code unresolved_reason} says. {@code CLOUD}
     * is a placeholder whose resolve downloads the file; it is also what a server that gives no
     * reason (or one this client does not know) reads as, as in the napari widget.
     */
    public enum Unresolved { NONE, CLOUD, PENDING, FAILED }

    /**
     * The unresolved state of a source row. Only the server's {@code is_resolved} decides it, not
     * an empty tensor list: a source that resolved and holds nothing is {@code NONE} (and gets no
     * row, see {@link #sourceNode}); an unresolved one has no tensors because they are unknown.
     */
    public static Unresolved unresolvedState(Map<String, Object> descriptor) {
        if (SourceList.isResolved(descriptor)) {
            return Unresolved.NONE;
        }
        Object why = descriptor.get("unresolved_reason");
        return "failed".equals(why) ? Unresolved.FAILED : "pending".equals(why) ? Unresolved.PENDING : Unresolved.CLOUD;
    }

    /** The user object of a tree node. */
    public static final class Node {
        private final Kind kind;
        private final String key;
        private final String text;
        private final String suffix;
        private final String tooltip;
        private final List<String> arrayIds;
        private final Unresolved unresolved;
        private String sourceId = ""; // for a tensor row, the source it is under

        Node(Kind kind, String key, String text, String suffix, String tooltip, List<String> arrayIds) {
            this(kind, key, text, suffix, tooltip, arrayIds, Unresolved.NONE);
        }

        Node(Kind kind, String key, String text, String suffix, String tooltip, List<String> arrayIds,
                Unresolved unresolved) {
            this.unresolved = unresolved;
            this.kind = kind;
            this.key = key;
            this.text = text;
            this.suffix = suffix;
            this.tooltip = tooltip;
            this.arrayIds = arrayIds;
        }

        /** The row's kind. */
        public Kind getKind() { return kind; }
        /** The folder key, source id or array id; empty for a note. */
        public String getKey() { return key; }
        /** The label. */
        public String getText() { return text; }
        /** Shape and dtype (or a count, or a status); empty if none. */
        public String getSuffix() { return suffix; }
        /** A hover text, or empty. */
        public String getTooltip() { return tooltip; }
        /** The tensors this row opens: one for a tensor or single-tensor source, all for a multi-tensor source. */
        public List<String> getArrayIds() { return arrayIds; }
        /** For a source row, why it is unresolved ({@link Unresolved#NONE} if it is not); it can be resolved unless NONE. */
        public Unresolved getUnresolved() { return unresolved; }
        /** The id of the source this row is or is under; empty for a folder or note. */
        public String getSourceId() { return kind == Kind.SOURCE ? key : sourceId; }
        /** Whether the row opens as one image (double-click). */
        public boolean isOpenable() { return arrayIds.size() == 1 && kind != Kind.FOLDER; }
        /** An identity that survives a rebuild, for selection. */
        public String getId() { return kind + ":" + key; }

        @Override
        public String toString() { return suffix.isEmpty() ? text : text + "  " + suffix; }
    }

    private static DefaultMutableTreeNode node(Kind kind, String key, String text, String suffix, String tip,
            List<String> ids) {
        return new DefaultMutableTreeNode(new Node(kind, key, text, suffix, tip, ids));
    }

    private static DefaultMutableTreeNode node(Kind kind, String key, String text, String suffix, String tip,
            List<String> ids, Unresolved unresolved) {
        return new DefaultMutableTreeNode(new Node(kind, key, text, suffix, tip, ids, unresolved));
    }

    private static DefaultMutableTreeNode note(String text) {
        return node(Kind.NOTE, "", text, "", "", Collections.<String>emptyList());
    }

    /** The user object of {@code n} as a {@link Node}, or {@code null}. */
    public static Node nodeOf(Object n) {
        Object o = n instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) n).getUserObject() : null;
        return o instanceof Node ? (Node) o : null;
    }

    // --- lazy levels ---------------------------------------------------------

    /** A root over the top level, or an empty root if it has not been read. */
    public static DefaultMutableTreeNode buildLevels(Map<String, CatalogClient.Level> levels,
            Map<String, Map<String, Object>> sources, Set<String> expanded, Set<String> failed) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        if (levels.containsKey("")) {
            for (DefaultMutableTreeNode n : levelChildren("", levels, sources, expanded, failed)) {
                root.add(n);
            }
        }
        return root;
    }

    /**
     * The rows of one folder. Only an open folder with a level read has children;
     * a closed one holds a placeholder (loading, or failed), so the work follows
     * what is on screen. A source whose descriptor is not held, or that holds
     * nothing, is left out.
     */
    public static List<DefaultMutableTreeNode> levelChildren(String key, Map<String, CatalogClient.Level> levels,
            Map<String, Map<String, Object>> sources, Set<String> expanded, Set<String> failed) {
        List<DefaultMutableTreeNode> out = new ArrayList<>();
        CatalogClient.Level level = levels.get(key);
        if (level == null) {
            out.add(note(failed.contains(key) ? FAILED_NOTE : LOADING_NOTE));
            return out;
        }
        for (CatalogClient.Folder f : level.getFolders()) {
            DefaultMutableTreeNode n = node(Kind.FOLDER, f.getKey(), f.getName(), "", f.getName(),
                    Collections.<String>emptyList());
            if (expanded.contains(f.getKey()) && levels.containsKey(f.getKey())) {
                for (DefaultMutableTreeNode c : levelChildren(f.getKey(), levels, sources, expanded, failed)) {
                    n.add(c);
                }
            } else {
                n.add(note(failed.contains(f.getKey()) ? FAILED_NOTE : LOADING_NOTE));
            }
            out.add(n);
        }
        for (CatalogClient.Source s : level.getSources()) {
            Map<String, Object> d = sources.get(s.getSourceId());
            DefaultMutableTreeNode n = d == null ? null : sourceNode(d, s.getName());
            if (n != null) {
                out.add(n);
            }
        }
        if (level.isTruncated()) {
            out.add(note(CUT_NOTE));
        }
        return out;
    }

    /** The folders of a tree by key, so one can be grafted in place. */
    public static Map<String, DefaultMutableTreeNode> folderIndex(DefaultMutableTreeNode root) {
        Map<String, DefaultMutableTreeNode> out = new LinkedHashMap<>();
        for (java.util.Enumeration<?> e = root.preorderEnumeration(); e.hasMoreElements();) {
            DefaultMutableTreeNode t = (DefaultMutableTreeNode) e.nextElement();
            Node n = nodeOf(t);
            if (n != null && n.kind == Kind.FOLDER) {
                out.put(n.key, t);
            }
        }
        return out;
    }

    // --- sources -------------------------------------------------------------

    /**
     * A source row, or {@code null} for a resolved source with nothing in it (an unresolved one keeps
     * its row, so it can be resolved). One
     * tensor makes a leaf; several make a parent with a row per tensor.
     */
    public static DefaultMutableTreeNode sourceNode(Map<String, Object> descriptor, String name) {
        String id = String.valueOf(descriptor.get("source_id"));
        List<Map<?, ?>> tensors = tensors(descriptor);
        Unresolved state = unresolvedState(descriptor);
        if (tensors.isEmpty() && state == Unresolved.NONE) {
            return null;
        }
        // The cloud / pending glyph leads the suffix; a failed source says so only when it has no shape to show.
        String glyph = state == Unresolved.CLOUD ? CLOUD_GLYPH : state == Unresolved.PENDING ? PENDING_GLYPH : "";
        String note = state == Unresolved.CLOUD ? CLOUD_NOTE : state == Unresolved.PENDING ? PENDING_NOTE : "";
        String tip = note.isEmpty() ? id : id + " \u2013 " + note;
        if (tensors.isEmpty()) {
            return node(Kind.SOURCE, id, name, state == Unresolved.FAILED ? "[failed]" : glyph, tip,
                    Collections.<String>emptyList(), state);
        }
        // Label sets ride under their image rather than counting as tensors of the source.
        LabelId.Grouping grouping = LabelId.group(tensors);
        List<LabelId.Group> groups = grouping.images;
        List<String> all = new ArrayList<>(); // the images only: "open all" never lays a mask implicitly
        for (LabelId.Group g : groups) {
            all.add(String.valueOf(g.image.get("array_id")));
        }
        DefaultMutableTreeNode n;
        if (groups.size() == 1) {
            n = node(Kind.SOURCE, id, name, withGlyph(glyph, tensorSuffix(groups.get(0).image)), tip, all, state);
            addLabelRows(n, groups.get(0).labelSets, id);
        } else {
            String count = groups.isEmpty() ? "" : groups.size() + " tensors";
            n = node(Kind.SOURCE, id, name, withGlyph(glyph, count), tip, all, state);
            for (LabelId.Group g : groups) {
                String array = String.valueOf(g.image.get("array_id"));
                DefaultMutableTreeNode row = node(Kind.TENSOR, array, shortName(array), tensorSuffix(g.image), array,
                        Collections.singletonList(array));
                nodeOf(row).sourceId = id;
                addLabelRows(row, g.labelSets, id);
                n.add(row);
            }
        }
        addLabelRows(n, grouping.orphans, id); // not under any image, and not counted above
        return n;
    }

    /** One LABEL row per set, under {@code parent}. */
    private static void addLabelRows(DefaultMutableTreeNode parent, List<Map<?, ?>> sets, String sourceId) {
        for (Map<?, ?> t : sets) {
            String array = String.valueOf(t.get("array_id"));
            LabelId.Address a = LabelId.parse(array);
            // The set's own name (and native level, which makes it a different tensor), not the last segment.
            String setName = a == null ? shortName(array) : LabelOpener.displayName(a);
            DefaultMutableTreeNode row = node(Kind.LABEL, array, LABEL_GLYPH + " " + setName, tensorSuffix(t),
                    "Label set \u201c" + setName + "\u201d \u2013 opens as a separate label image", Collections.singletonList(array));
            nodeOf(row).sourceId = sourceId;
            parent.add(row);
        }
    }

    /** How many image tensors (label sets not counted) the source row lists. */
    public static int imageCount(Map<String, Object> descriptor) {
        return LabelId.group(tensors(descriptor)).images.size();
    }

    private static String withGlyph(String glyph, String suffix) {
        return glyph.isEmpty() ? suffix : suffix.isEmpty() ? glyph : glyph + " " + suffix;
    }

    private static List<Map<?, ?>> tensors(Map<String, Object> descriptor) {
        List<Map<?, ?>> out = new ArrayList<>();
        Object raw = descriptor.get("tensors");
        if (raw instanceof Collection) {
            for (Object o : (Collection<?>) raw) {
                if (o instanceof Map && ((Map<?, ?>) o).get("array_id") != null) {
                    out.add((Map<?, ?>) o);
                }
            }
        }
        return out;
    }

    /** The last segment of an array id. */
    public static String shortName(String arrayId) {
        String[] parts = arrayId.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty()) {
                return parts[i];
            }
        }
        return arrayId;
    }

    private static String tensorSuffix(Map<?, ?> tensor) {
        StringBuilder shape = new StringBuilder();
        Object raw = tensor.get("shape");
        if (raw instanceof Collection) {
            List<Object> dims = new ArrayList<>((Collection<?>) raw);
            while (dims.size() > 1 && isOne(dims.get(0))) { // squeeze leading singleton axes
                dims.remove(0);
            }
            for (Object d : dims) {
                shape.append(shape.length() == 0 ? "" : "×").append(d);
            }
        }
        Object dtype = tensor.get("dtype");
        String d = dtype == null ? "" : dtype.toString();
        return shape.length() == 0 ? d : d.isEmpty() ? shape.toString() : shape + " " + d;
    }

    private static boolean isOne(Object d) {
        return d instanceof Number && ((Number) d).longValue() == 1;
    }

    // --- by URL --------------------------------------------------------------

    /**
     * The path of a source URL as folder names: split on both separators, a drive
     * letter dropped, and an authority URL ({@code grpc://host:port/p}) led by its
     * endpoint so mirrored sources nest under it.
     */
    public static List<String> pathParts(String url) {
        List<String> parts = new ArrayList<>();
        if (url == null || url.isEmpty()) {
            return parts;
        }
        String raw = url;
        if (url.startsWith("dnd://")) {
            raw = url.substring("dnd://".length());
        } else {
            try {
                URI u = new URI(url);
                if (u.getScheme() != null && u.getRawAuthority() != null) {
                    parts.add(u.getScheme() + "://" + u.getRawAuthority());
                    raw = u.getPath() == null ? "" : u.getPath();
                } else if (u.getScheme() != null && u.getPath() != null) {
                    raw = u.getPath();
                }
            } catch (java.net.URISyntaxException bad) { // a Windows path, say: split it whole
                raw = url;
            }
        }
        for (String p : SEPARATORS.split(raw)) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        if (!parts.isEmpty() && DRIVE.matcher(parts.get(0)).matches()) {
            parts.remove(0);
        }
        return parts;
    }

    /**
     * A tree of {@code sources} by their URLs: folders first, then sources, each
     * sorted by name, and a folder holding only one folder merged into it
     * ({@code a/b}). Folder keys are the paths, which is what open state is kept by.
     */
    public static DefaultMutableTreeNode buildPaths(Collection<Map<String, Object>> sources) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        Map<String, DefaultMutableTreeNode> folders = new LinkedHashMap<>();
        for (Map<String, Object> d : sources) {
            List<String> parts = pathParts(d.get("source_url") == null ? "" : d.get("source_url").toString());
            String name = parts.isEmpty() ? String.valueOf(d.get("source_id")) : parts.get(parts.size() - 1);
            DefaultMutableTreeNode leaf = sourceNode(d, name);
            if (leaf == null) {
                continue;
            }
            DefaultMutableTreeNode parent = root;
            String path = "";
            for (int i = 0; i < parts.size() - 1; i++) {
                path = path + "/" + parts.get(i);
                DefaultMutableTreeNode f = folders.get(path);
                if (f == null) {
                    f = node(Kind.FOLDER, path, parts.get(i), "", path, Collections.<String>emptyList());
                    folders.put(path, f);
                    parent.add(f);
                }
                parent = f;
            }
            parent.add(leaf);
        }
        sortAndMerge(root);
        return root;
    }

    private static final Comparator<DefaultMutableTreeNode> ORDER = (a, b) -> {
        Node x = nodeOf(a);
        Node y = nodeOf(b);
        int k = Integer.compare(x.kind == Kind.FOLDER ? 0 : 1, y.kind == Kind.FOLDER ? 0 : 1);
        return k != 0 ? k : x.text.compareToIgnoreCase(y.text);
    };

    private static void sortAndMerge(DefaultMutableTreeNode parent) {
        List<DefaultMutableTreeNode> kids = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            kids.add((DefaultMutableTreeNode) parent.getChildAt(i));
        }
        Collections.sort(kids, ORDER);
        parent.removeAllChildren();
        for (DefaultMutableTreeNode k : kids) {
            Node n = nodeOf(k);
            if (n.kind == Kind.FOLDER) {
                sortAndMerge(k);
                while (k.getChildCount() == 1 && nodeOf(k.getChildAt(0)).kind == Kind.FOLDER) {
                    DefaultMutableTreeNode only = (DefaultMutableTreeNode) k.getChildAt(0);
                    Node o = nodeOf(only);
                    DefaultMutableTreeNode merged = node(Kind.FOLDER, o.key, n.text + "/" + o.text, "", o.key,
                            Collections.<String>emptyList());
                    while (only.getChildCount() > 0) {
                        merged.add((DefaultMutableTreeNode) only.getChildAt(0));
                    }
                    k = merged;
                    n = nodeOf(k);
                }
            }
            parent.add(k);
        }
    }

    /** The keys of the folders {@code depth} levels from the top, to open so a search shows where its matches are. */
    public static Set<String> topFolders(DefaultMutableTreeNode root, int depth) {
        Set<String> out = new java.util.LinkedHashSet<>();
        collect(root, depth, out);
        return out;
    }

    private static void collect(DefaultMutableTreeNode parent, int depth, Set<String> out) {
        if (depth <= 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) parent.getChildAt(i);
            Node n = nodeOf(c);
            if (n.kind == Kind.FOLDER) {
                out.add(n.key);
                collect(c, depth - 1, out);
            }
        }
    }

    // --- search --------------------------------------------------------------

    /**
     * The statement for a search ({@code query} lowercased and trimmed): the
     * {@code columns} of the sources whose id, URL or type contain it, in URL
     * order, one past {@link #SEARCH_LIMIT} so a cut result can be told.
     */
    public static String searchSql(String query, String columns) {
        String esc = query.replace("\\", "\\\\").replace("'", "''").replace("%", "\\%").replace("_", "\\_");
        return "SELECT " + columns + " FROM sources WHERE "
                + "LOWER(source_id) LIKE '%" + esc + "%' ESCAPE '\\' OR "
                + "LOWER(source_url) LIKE '%" + esc + "%' ESCAPE '\\' OR "
                + "LOWER(source_type) LIKE '%" + esc + "%' ESCAPE '\\' "
                + "ORDER BY source_url LIMIT " + (SEARCH_LIMIT + 1);
    }
}
