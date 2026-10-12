package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.jiyuuchc.biopb.fiji.TreeModelBuilder.Kind;
import io.github.jiyuuchc.biopb.fiji.TreeModelBuilder.Node;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.Test;

/** Tree model building: no display needed. */
public class TreeModelBuilderTest {

    static Map<String, Object> tensor(String arrayId, String dtype, Object... shape) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("array_id", arrayId);
        t.put("shape", Arrays.asList(shape));
        t.put("dtype", dtype);
        return t;
    }

    @SafeVarargs
    static Map<String, Object> source(String id, String url, Map<String, Object>... tensors) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source_id", id);
        d.put("source_url", url);
        d.put("is_resolved", true);
        d.put("tensors", Arrays.asList(tensors));
        return d;
    }

    static CatalogClient.Level level(boolean truncated, List<CatalogClient.Folder> f, CatalogClient.Source... s) {
        return new CatalogClient.Level("", f, Arrays.asList(s), truncated);
    }

    static CatalogClient.Folder folder(String key) {
        return new CatalogClient.Folder(key, key);
    }

    static Map<String, Map<String, Object>> sources(Map<String, Object>... ds) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> d : ds) {
            m.put((String) d.get("source_id"), d);
        }
        return m;
    }

    static List<Node> kids(DefaultMutableTreeNode n) {
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < n.getChildCount(); i++) {
            out.add(TreeModelBuilder.nodeOf(n.getChildAt(i)));
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void closedFolderHoldsALoadingPlaceholderAndSourcesCarryASuffix() {
        Map<String, CatalogClient.Level> levels = new LinkedHashMap<>();
        levels.put("", level(false, Arrays.asList(folder("a")), new CatalogClient.Source("img", "s1")));
        DefaultMutableTreeNode root = TreeModelBuilder.buildLevels(levels,
                sources(source("s1", "/x/img", tensor("s1/0", "uint16", 1, 1, 512, 256))),
                Collections.<String>emptySet(), Collections.<String>emptySet());
        List<Node> top = kids(root);
        assertEquals(2, top.size());
        assertEquals(Kind.FOLDER, top.get(0).getKind());
        assertEquals(Kind.NOTE, TreeModelBuilder.nodeOf(root.getChildAt(0).getChildAt(0)).getKind());
        assertEquals(TreeModelBuilder.LOADING_NOTE, TreeModelBuilder.nodeOf(root.getChildAt(0).getChildAt(0)).getText());
        Node src = top.get(1);
        assertEquals("512\u00d7256 uint16", src.getSuffix());
        assertTrue(src.isOpenable());
        assertEquals(Arrays.asList("s1/0"), src.getArrayIds());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void openFolderWithALevelShowsItsRowsAndAFailedOneShowsTheNote() {
        Map<String, CatalogClient.Level> levels = new LinkedHashMap<>();
        levels.put("", level(false, Arrays.asList(folder("a"), folder("b"))));
        levels.put("a", level(true, Collections.<CatalogClient.Folder>emptyList(), new CatalogClient.Source("i", "s1")));
        Set<String> open = new HashSet<>(Arrays.asList("a", "b"));
        Set<String> failed = new HashSet<>(Arrays.asList("b"));
        DefaultMutableTreeNode root = TreeModelBuilder.buildLevels(levels,
                sources(source("s1", "u", tensor("s1/0", "uint8", 4, 4))), open, failed);
        List<Node> a = kids((DefaultMutableTreeNode) root.getChildAt(0));
        assertEquals("i", a.get(0).getText());
        assertEquals(TreeModelBuilder.CUT_NOTE, a.get(1).getText()); // truncated
        assertEquals(TreeModelBuilder.FAILED_NOTE, kids((DefaultMutableTreeNode) root.getChildAt(1)).get(0).getText());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void expandedSubfoldersAreBuiltFromTheirLevelsOnARebuild() {
        Map<String, CatalogClient.Level> levels = new LinkedHashMap<>();
        levels.put("", level(false, Arrays.asList(folder("a"))));
        levels.put("a", level(false, Arrays.asList(folder("a/b"))));
        levels.put("a/b", level(false, Collections.<CatalogClient.Folder>emptyList(), new CatalogClient.Source("deep", "s1")));
        Set<String> open = new HashSet<>(Arrays.asList("a", "a/b"));
        DefaultMutableTreeNode root = TreeModelBuilder.buildLevels(levels,
                sources(source("s1", "u", tensor("s1/0", "uint8", 4, 4))), open, Collections.<String>emptySet());
        assertEquals("deep", TreeModelBuilder.nodeOf(
                TreeModelBuilder.folderIndex(root).get("a/b").getChildAt(0)).getText());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void emptyAndUndescribedSourcesAreLeftOut() {
        Map<String, Object> empty = source("e", "u");
        assertNull(TreeModelBuilder.sourceNode(empty, "e"));
        Map<String, Object> pending = source("p", "u");
        pending.put("is_resolved", false);
        pending.put("unresolved_reason", "failed");
        assertEquals("[failed]", TreeModelBuilder.nodeOf(TreeModelBuilder.sourceNode(pending, "p")).getSuffix());
        Map<String, CatalogClient.Level> levels = new LinkedHashMap<>();
        levels.put("", level(false, Collections.<CatalogClient.Folder>emptyList(), new CatalogClient.Source("n", "gone")));
        assertEquals(0, TreeModelBuilder.buildLevels(levels, sources(), new HashSet<String>(), new HashSet<String>())
                .getChildCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void multiTensorSourceHasARowPerTensor() {
        DefaultMutableTreeNode n = TreeModelBuilder.sourceNode(
                source("s", "u", tensor("s/a", "uint8", 8), tensor("s/b", "float32", 2, 3)), "s");
        Node p = TreeModelBuilder.nodeOf(n);
        assertEquals("2 tensors", p.getSuffix());
        assertEquals(Arrays.asList("s/a", "s/b"), p.getArrayIds());
        assertTrue(!p.isOpenable());
        assertEquals("2\u00d73 float32", kids(n).get(1).getSuffix());
    }

    @Test
    public void pathPartsHandleSchemesWindowsAndAuthorities() {
        assertEquals(Arrays.asList("data", "a.zarr"), TreeModelBuilder.pathParts("file:///data/a.zarr"));
        assertEquals(Arrays.asList("Users", "me", "i.tif"), TreeModelBuilder.pathParts("C:\\Users\\me\\i.tif"));
        assertEquals(Arrays.asList("grpc://host:1", "r", "x"), TreeModelBuilder.pathParts("grpc://host:1/r/x"));
        assertEquals(Arrays.asList("d", "e"), TreeModelBuilder.pathParts("dnd://d/e"));
        assertTrue(TreeModelBuilder.pathParts("").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void pathTreeSortsFoldersFirstAndMergesSingleFolderChains() {
        DefaultMutableTreeNode root = TreeModelBuilder.buildPaths(Arrays.asList(
                source("1", "/d/x/y/b.tif", tensor("1/0", "uint8", 2)),
                source("2", "/d/x/y/a.tif", tensor("2/0", "uint8", 2)),
                source("3", "/d/top.tif", tensor("3/0", "uint8", 2))));
        List<Node> top = kids(root);
        assertEquals(1, top.size()); // d only
        assertEquals("d", top.get(0).getText());
        List<Node> d = kids((DefaultMutableTreeNode) root.getChildAt(0));
        assertEquals("x/y", d.get(0).getText()); // merged chain, folder first
        assertEquals("/d/x/y", d.get(0).getKey());
        assertEquals("top.tif", d.get(1).getText());
        List<Node> xy = kids((DefaultMutableTreeNode) root.getChildAt(0).getChildAt(0));
        assertEquals("a.tif", xy.get(0).getText());
        assertEquals(new HashSet<>(Arrays.asList("/d")), TreeModelBuilder.topFolders(root, 1));
    }

    @Test
    public void searchSqlEscapesTheQuery() {
        String sql = TreeModelBuilder.searchSql("a_b%c'd\\", "source_id");
        assertTrue(sql, sql.contains("LIKE '%a\\_b\\%c''d\\\\%' ESCAPE '\\'"));
        assertTrue(sql.startsWith("SELECT source_id FROM sources WHERE LOWER(source_id) LIKE"));
        assertTrue(sql.contains("LOWER(source_url) LIKE") && sql.contains("LOWER(source_type) LIKE"));
        assertTrue(sql.endsWith("ORDER BY source_url LIMIT 2001"));
    }

    @Test
    public void addressParsing() {
        assertEquals("localhost:8815", TensorBrowserPanel.defaultAddress(k -> null));
        assertEquals("h:1", TensorBrowserPanel.defaultAddress(k -> " h:1 "));
        Object[] hp = TensorBrowserPanel.parseAddress("grpc://example.org:9000/");
        assertEquals("example.org", hp[0]);
        assertEquals(9000, hp[1]);
        assertEquals(8815, TensorBrowserPanel.parseAddress("example.org")[1]);
        assertEquals("[::1]", TensorBrowserPanel.parseAddress("[::1]")[0]);
        assertEquals(8815, TensorBrowserPanel.parseAddress("[::1]")[1]);
        assertEquals(9, TensorBrowserPanel.parseAddress("grpc://[::1]:9/x/y")[1]);
        assertEquals("h", TensorBrowserPanel.parseAddress("grpc://h:5/path")[0]);
    }
}
