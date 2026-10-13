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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.junit.After;
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
        final AtomicBoolean searchFails = new AtomicBoolean();
        volatile CountDownLatch queryGate; // holds the first query open when set
        final AtomicInteger queryCalls = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

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
        public List<Map<String, Object>> query(String sql) throws IOException {
            threads.add(Thread.currentThread().getName());
            queries.add(sql);
            CountDownLatch g = queryGate;
            if (g != null && queryCalls.getAndIncrement() == 0) {
                try {
                    g.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (searchFails.get()) {
                throw new IOException("down");
            }
            Map<String, Object> d = TreeModelBuilderTest.source("s1", "/data/a/img.zarr",
                    TreeModelBuilderTest.tensor("s1/0", "uint16", 1, 64, 32));
            return new ArrayList<>(Collections.singletonList(d));
        }

        @Override public Map<String, Object> healthCheck() { return new LinkedHashMap<>(); }
        @Override public String sourceRowColumns() { return null; }
    }

    private Fake fake;
    private Fake second;
    private TensorBrowserPanel panel;
    private final List<Fake> opened = new CopyOnWriteArrayList<>();
    private final List<String> connectThreads = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch gate; // holds a connect open when set

    @Before
    public void display() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        fake = new Fake();
        second = new Fake();
        panel = new TensorBrowserPanel((host, port) -> {
            connectThreads.add(Thread.currentThread().getName());
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            Fake f = opened.isEmpty() ? fake : second;
            opened.add(f);
            return new TensorBrowserPanel.Connection() {
                @Override public CatalogClient catalog() { return f; }
                @Override public biopb.tensor.TensorFlightClient flight() { return null; }
                @Override public void close() { f.closed.incrementAndGet(); }
            };
        }, "h:1");
    }

    @After
    public void cleanup() throws Exception {
        if (panel != null) {
            edt(panel::dispose);
        }
    }

    private void assertNothingOnEdt() {
        List<String> all = new ArrayList<>(connectThreads);
        all.addAll(fake.threads);
        all.addAll(second.threads);
        assertFalse(all.isEmpty());
        for (String t : all) {
            assertFalse("server call on the EDT: " + t, t.startsWith("AWT-EventQueue"));
        }
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
        assertNothingOnEdt();
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
        assertNothingOnEdt();
        edt(() -> panel.getSearchField().setText(""));
        await(() -> child("a") != null);
    }

    @Test
    public void failedSearchClearsTheStaleMatches() throws Exception {
        connect();
        edt(() -> panel.getSearchField().setText("img"));
        await(() -> panel.getSearchStatusLabel().getText().contains("1 match"));
        fake.searchFails.set(true);
        edt(() -> panel.getSearchField().setText("imgx"));
        await(() -> panel.getSearchStatusLabel().getText().contains("failed"));
        assertEquals(0, ((DefaultMutableTreeNode) panel.getTree().getModel().getRoot()).getChildCount());
    }

    @Test
    public void watcherRerunKeepsTheSearchViewOpenState() throws Exception {
        connect();
        edt(() -> panel.getSearchField().setText("img"));
        await(() -> panel.getSearchStatusLabel().getText().contains("1 match"));
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.getTree().getModel().getRoot();
        TreePath folder = new TreePath(((DefaultMutableTreeNode) root.getChildAt(0)).getPath());
        assertTrue(panel.getTree().isExpanded(folder));
        edt(() -> panel.getTree().collapsePath(folder)); // the user closes it
        int before = fake.queries.size();
        edt(panel::simulateCatalogChange);
        await(() -> fake.queries.size() > before && panel.getSearchStatusLabel().getText().contains("1 match"));
        edt(() -> assertFalse(panel.getTree().isExpanded(new TreePath(
                ((DefaultMutableTreeNode) ((DefaultMutableTreeNode) panel.getTree().getModel().getRoot())
                        .getChildAt(0)).getPath()))));
    }

    @Test
    public void reconnectAndDisposeCloseTheOldConnection() throws Exception {
        connect();
        edt(panel::connect);
        await(() -> opened.size() == 2 && panel.getConnection() != null
                && panel.getStatusLabel().getText().startsWith("Connected"));
        await(() -> fake.closed.get() == 1);
        assertEquals(0, second.closed.get());
        edt(panel::dispose);
        await(() -> second.closed.get() == 1);
        assertNothingOnEdt();
    }

    @Test
    public void connectResultAfterDisposeIsClosed() throws Exception {
        gate = new CountDownLatch(1);
        edt(panel::connect); // held open
        edt(panel::dispose);
        gate.countDown();
        await(() -> opened.size() == 1 && opened.get(0).closed.get() == 1);
        assertEquals(null, panel.getConnection());
    }

    @Test
    public void graftingLeavesOtherFoldersAlone() throws Exception {
        connect();
        TreePath bad = new TreePath(child("bad").getPath());
        edt(() -> panel.getTree().expandPath(bad));
        await(() -> TreeModelBuilder.FAILED_NOTE.equals(TreeModelBuilder.nodeOf(child("bad").getChildAt(0)).getText()));
        DefaultMutableTreeNode badNode = child("bad");
        edt(() -> panel.getTree().expandPath(new TreePath(child("a").getPath())));
        await(() -> TreeModelBuilder.nodeOf(child("a").getChildAt(0)).getKind() == TreeModelBuilder.Kind.SOURCE);
        edt(() -> {
            assertTrue(panel.getTree().isExpanded(bad)); // untouched by a's graft
            assertTrue(badNode == child("bad"));
        });
    }

    @Test
    public void doubleClickAndMenuAskTheListener() throws Exception {
        connect();
        edt(() -> panel.getTree().expandPath(new TreePath(child("a").getPath())));
        await(() -> TreeModelBuilder.nodeOf(child("a").getChildAt(0)).getKind() == TreeModelBuilder.Kind.SOURCE);
        List<String> calls = new CopyOnWriteArrayList<>();
        panel.addListener(new TensorBrowserListener() {
            @Override public void openRequested(String arrayId, int level) { calls.add("open " + arrayId + " " + level); }
            @Override public void roisRequested(String arrayId) { calls.add("rois " + arrayId); }
        });
        edt(() -> {
            javax.swing.JTree t = panel.getTree();
            t.setSize(400, 300);
            java.awt.Rectangle r = t.getPathBounds(new TreePath(
                    ((DefaultMutableTreeNode) child("a").getChildAt(0)).getPath()));
            java.awt.Point p = new java.awt.Point(r.x + 5, r.y + r.height / 2);
            for (java.awt.event.MouseListener ml : t.getMouseListeners()) {
                if (!(ml.getClass().getName().contains("javax.swing"))) {
                    ml.mouseClicked(new java.awt.event.MouseEvent(t, java.awt.event.MouseEvent.MOUSE_CLICKED,
                            System.currentTimeMillis(), 0, p.x, p.y, 2, false, java.awt.event.MouseEvent.BUTTON1));
                }
            }
        });
        assertEquals(Arrays.asList("open s1/0 0"), calls);
        DefaultMutableTreeNode n = TreeModelBuilder.sourceNode(TreeModelBuilderTest.source("s1", "u",
                TreeModelBuilderTest.tensor("s1/0", "uint8", 2)), "x");
        edt(() -> {
            javax.swing.JPopupMenu m = panel.menuFor(TreeModelBuilder.nodeOf(n));
            assertEquals(4, m.getComponentCount()); // Open, coarser, ROIs, Metadata…
            ((javax.swing.JMenuItem) m.getComponent(2)).doClick();
        });
        assertEquals(Arrays.asList("open s1/0 0", "rois s1/0"), calls);
        assertNotNull(panel.getConnection());
    }

    @Test
    public void aLabelSetRowOffersOpenButNotRois() throws Exception {
        DefaultMutableTreeNode n = TreeModelBuilder.sourceNode(TreeModelBuilderTest.source("s1", "u",
                TreeModelBuilderTest.tensor("s1/0", "uint8", 2),
                TreeModelBuilderTest.tensor("s1/0/@labels/m", "uint8", 2)), "x");
        edt(() -> {
            javax.swing.JPopupMenu m = panel.menuFor(TreeModelBuilder.nodeOf(n.getChildAt(0)));
            assertEquals(3, m.getComponentCount()); // Open, Open at coarser level, Metadata (no Load ROIs)
            assertEquals("Open", ((javax.swing.JMenuItem) m.getComponent(0)).getText());
            assertEquals(4, panel.menuFor(TreeModelBuilder.nodeOf(n)).getComponentCount());
        });
    }

    private int rootChildren() {
        return ((DefaultMutableTreeNode) panel.getTree().getModel().getRoot()).getChildCount();
    }

    @Test
    public void watcherRerunCarriesTheFreshStepOfASearchInFlight() throws Exception {
        connect();
        fake.queryGate = new CountDownLatch(1);
        edt(() -> panel.getSearchField().setText("img"));
        await(() -> fake.queries.size() == 1); // out, held
        edt(panel::simulateCatalogChange); // supersedes it
        await(() -> fake.queries.size() == 2);
        fake.queryGate.countDown();
        await(() -> panel.getSearchStatusLabel().getText().contains("1 match"));
        edt(() -> assertTrue(panel.getTree().isExpanded(new TreePath(
                ((DefaultMutableTreeNode) ((DefaultMutableTreeNode) panel.getTree().getModel().getRoot())
                        .getChildAt(0)).getPath()))));
    }

    @Test
    public void failedFreshSearchRetriesOnResubmit() throws Exception {
        connect();
        fake.searchFails.set(true);
        edt(() -> panel.getSearchField().setText("img"));
        await(() -> panel.getSearchStatusLabel().getText().contains("failed"));
        assertEquals(0, rootChildren());
        fake.searchFails.set(false);
        edt(panel::applySearch); // the same text again
        await(() -> panel.getSearchStatusLabel().getText().contains("1 match"));
        assertEquals(1, rootChildren());
    }

    @Test
    public void failedWatcherRerunKeepsThePreviousResults() throws Exception {
        connect();
        edt(() -> panel.getSearchField().setText("img"));
        await(() -> panel.getSearchStatusLabel().getText().contains("1 match"));
        fake.searchFails.set(true);
        edt(panel::simulateCatalogChange);
        await(() -> panel.getSearchStatusLabel().getText().contains("previous results"));
        assertEquals(1, rootChildren());
    }

    @Test
    public void connectDisablesTheControlsAndIgnoresAnOverlappingConnect() throws Exception {
        gate = new CountDownLatch(1);
        edt(panel::connect);
        edt(() -> {
            assertFalse(panel.getConnectButton().isEnabled());
            assertFalse(panel.getAddressField().isEnabled());
            panel.connect(); // Enter in the field would do this
        });
        gate.countDown();
        await(() -> panel.getConnection() != null && panel.getConnectButton().isEnabled());
        assertEquals(1, connectThreads.size());
        assertTrue(panel.getAddressField().isEnabled());
    }
}
