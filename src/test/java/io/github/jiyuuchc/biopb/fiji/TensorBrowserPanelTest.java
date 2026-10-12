package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** The panel against a fake server: connect, lazy open, search, threading. Needs a display (xvfb in CI). */
public class TensorBrowserPanelTest {

    /** A lazy server: top has folder "a" (holding one image) and "bad" (whose read fails). */
    static final class Fake implements CatalogClient {
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<String> queries = new CopyOnWriteArrayList<>();
        final AtomicBoolean badFails = new AtomicBoolean(true);

        @Override
        public Level catalogChildren(String key, int limit) throws IOException {
            threads.add(Thread.currentThread().getName());
            List<Folder> none = Collections.<Folder>emptyList();
            switch (key) {
                case "":
                    return new Level("", Arrays.asList(new Folder("a", "a"), new Folder("bad", "bad")),
                            Collections.<Source>emptyList(), false);
                case "a":
                    return new Level("a", none, Arrays.asList(new Source("img.zarr", "s1")), false);
                default:
                    if (badFails.get()) {
                        throw new IOException("boom");
                    }
                    return new Level(key, none, Collections.<Source>emptyList(), false);
            }
        }

        @Override
        public List<Map<String, Object>> query(String sql) {
            queries.add(sql);
            Map<String, Object> d = TreeModelBuilderTest.source("s1", "/data/a/img.zarr",
                    TreeModelBuilderTest.tensor("s1/0", "uint16", 1, 64, 32));
            return new ArrayList<>(Collections.singletonList(d));
        }

        @Override public Map<String, Object> healthCheck() { return new LinkedHashMap<>(); }
        @Override public String sourceRowColumns() { return null; }
    }

    private Fake fake;
    private TensorBrowserPanel panel;

    @Before
    public void display() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        fake = new Fake();
        panel = new TensorBrowserPanel((host, port) -> new TensorBrowserPanel.Connection() {
            @Override public CatalogClient catalog() { return fake; }
            @Override public biopb.tensor.TensorFlightClient flight() { return null; }
            @Override public void close() { }
        }, "h:1");
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static void await(BooleanSupplier c) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = c.getAsBoolean());
            if (ok[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out");
    }

    private DefaultMutableTreeNode child(String text) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.getTree().getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            if (TreeModelBuilder.nodeOf(root.getChildAt(i)).getText().equals(text)) {
                return (DefaultMutableTreeNode) root.getChildAt(i);
            }
        }
        return null;
    }

    private void connect() throws Exception {
        edt(panel::connect);
        await(() -> child("a") != null);
    }

    @Test
    public void connectBuildsTheTopLevelOffTheEdtAndExpandingGraftsTheFolder() throws Exception {
        connect();
        assertTrue(panel.getStatusLabel().getText(), panel.getStatusLabel().getText().startsWith("Connected"));
        assertEquals(TreeModelBuilder.LOADING_NOTE, TreeModelBuilder.nodeOf(child("a").getChildAt(0)).getText());
        edt(() -> panel.getTree().expandPath(new TreePath(child("a").getPath())));
        await(() -> TreeModelBuilder.nodeOf(child("a").getChildAt(0)).getKind() == TreeModelBuilder.Kind.SOURCE);
        assertTrue(panel.getTree().isExpanded(new TreePath(child("a").getPath())));
        for (String t : fake.threads) {
            assertFalse("read on the EDT: " + t, t.startsWith("AWT-EventQueue"));
        }
    }

    @Test
    public void failedFolderRetriesWhenReopened() throws Exception {
        connect();
        TreePath bad = new TreePath(child("bad").getPath());
        edt(() -> panel.getTree().expandPath(bad));
        await(() -> TreeModelBuilder.FAILED_NOTE.equals(TreeModelBuilder.nodeOf(child("bad").getChildAt(0)).getText()));
        fake.badFails.set(false);
        edt(() -> {
            panel.getTree().collapsePath(bad);
            panel.getTree().expandPath(bad);
        });
        await(() -> child("bad").getChildCount() == 0);
    }

    @Test
    public void searchRunsOnTheServerAndClearingRestoresTheFolderTree() throws Exception {
        connect();
        edt(() -> panel.getSearchField().setText("IMG"));
        await(() -> !fake.queries.isEmpty() && panel.getSearchStatusLabel().getText().contains("1 match"));
        assertTrue(fake.queries.get(0).contains("%img%"));
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.getTree().getModel().getRoot();
        assertEquals("data/a", TreeModelBuilder.nodeOf(root.getChildAt(0)).getText());
        edt(() -> panel.getSearchField().setText(""));
        await(() -> child("a") != null);
    }

    @Test
    public void doubleClickAndMenuAskTheListener() throws Exception {
        connect();
        List<String> calls = new CopyOnWriteArrayList<>();
        panel.addListener(new TensorBrowserListener() {
            @Override public void openRequested(String arrayId, int level) { calls.add("open " + arrayId + " " + level); }
            @Override public void roisRequested(String arrayId) { calls.add("rois " + arrayId); }
        });
        DefaultMutableTreeNode n = TreeModelBuilder.sourceNode(TreeModelBuilderTest.source("s1", "u",
                TreeModelBuilderTest.tensor("s1/0", "uint8", 2)), "x");
        edt(() -> {
            javax.swing.JPopupMenu m = panel.menuFor(TreeModelBuilder.nodeOf(n));
            assertEquals(3, m.getComponentCount());
            ((javax.swing.JMenuItem) m.getComponent(0)).doClick();
            ((javax.swing.JMenuItem) m.getComponent(2)).doClick();
        });
        assertEquals(Arrays.asList("open s1/0 0", "rois s1/0"), calls);
        assertNotNull(panel.getConnection());
    }
}
