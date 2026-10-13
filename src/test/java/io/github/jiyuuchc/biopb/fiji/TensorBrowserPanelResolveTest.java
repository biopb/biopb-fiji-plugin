package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Resolving unresolved sources from the panel, against a fake server that streams progress. Needs a display. */
public class TensorBrowserPanelResolveTest {

    /** Three unresolved sources at the top; resolving "cloud" streams a heartbeat, then waits to be released or cancelled. */
    static final class Fake implements CatalogClient {
        final AtomicInteger resolveCalls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile String failWith;
        volatile boolean sawCancel;

        static Map<String, Object> row(String id, String url, String reason) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source_id", id);
            row.put("source_url", url);
            row.put("is_resolved", false);
            row.put("tensors", new ArrayList<>());
            row.put("unresolved_reason", reason);
            return row;
        }

        @Override
        public Level catalogChildren(String key, int limit) {
            return new Level("", Collections.<Folder>emptyList(), Arrays.asList(
                    new Source("cloud.svs", "cloud"), new Source("pending.svs", "pending"),
                    new Source("failed.svs", "failed")), false);
        }

        @Override
        public List<Map<String, Object>> query(String sql) {
            return new ArrayList<>(Arrays.asList(row("cloud", "/x/cloud.svs", "needs_recall"),
                    row("pending", "/x/pending.svs", "pending"), row("failed", "/x/failed.svs", "failed")));
        }

        @Override
        public Map<String, Object> resolveSource(String id, Consumer<ResolveProgress> onProgress,
                BooleanSupplier shouldCancel) throws IOException {
            resolveCalls.incrementAndGet();
            onProgress.accept(new ResolveProgress(3.2, "cloud.svs", 2048));
            started.countDown();
            try {
                while (!release.await(10, TimeUnit.MILLISECONDS)) {
                    if (shouldCancel.getAsBoolean()) {
                        sawCancel = true;
                        throw new ResolveCancelledException(id);
                    }
                }
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            if (failWith != null) {
                throw new IOException(failWith);
            }
            Map<String, Object> done = row(id, "/x/cloud.svs", null);
            done.put("is_resolved", true);
            done.put("tensors", Arrays.asList(TreeModelBuilderTest.tensor("cloud/0", "uint8", 4, 4)));
            return done;
        }

        @Override public Map<String, Object> healthCheck() { return new LinkedHashMap<>(); }
        @Override public String sourceRowColumns() { return null; }
    }

    private Fake fake;
    private TensorBrowserPanel panel;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final List<String> heard = new CopyOnWriteArrayList<>();
    private volatile boolean consent = true;

    @Before
    public void display() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        fake = new Fake();
        panel = new TensorBrowserPanel((host, port) -> new TensorBrowserPanel.Connection() {
            @Override public CatalogClient catalog() { return fake; }
            @Override public biopb.tensor.TensorFlightClient flight() { return null; }
            @Override public void close() { }
        }, "h:1");
        panel.confirmDownload = (title, message) -> {
            asked.add(message);
            return consent;
        };
        panel.addListener(new TensorBrowserListener() {
            @Override public void openRequested(String arrayId, int level) { }
            @Override public void roisRequested(String arrayId) { }
            @Override public void resolveStatus(String text, boolean busy) { heard.add(text + "|" + busy); }
        });
        edt(panel::connect);
        await(() -> child("cloud.svs") != null);
    }

    @After
    public void cleanup() throws Exception {
        if (panel != null) {
            fake.release.countDown();
            edt(panel::dispose);
        }
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static void await(BooleanSupplier c) throws Exception {
        for (int i = 0; i < 200; i++) {
            boolean[] ok = new boolean[1];
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

    private String status() {
        return panel.getStatusLabel().getText();
    }

    @Test
    public void resolveRunsOffTheEdtShowsProgressAndReplacesOnlyThatRow() throws Exception {
        DefaultMutableTreeNode pendingRow = child("pending.svs");
        edt(() -> panel.resolveSource("cloud"));
        assertEquals(1, asked.size()); // a cloud download asks first
        assertTrue(asked.get(0).contains("downloads the entire file"));
        assertTrue(fake.started.await(10, TimeUnit.SECONDS));
        await(() -> status().contains("3s (2.0 KB)"));
        assertTrue(panel.getCancelResolveButton().isVisible());
        assertTrue(heard.get(heard.size() - 1).endsWith("|true"));

        edt(() -> panel.resolveSource("cloud")); // a second request is ignored
        assertTrue(status(), status().contains("already being resolved"));
        assertEquals(1, fake.resolveCalls.get());

        fake.release.countDown();
        await(() -> status().startsWith("Resolved"));
        assertEquals("Resolved 'cloud.svs': 1 tensor", status());
        assertEquals("4×4 uint8", TreeModelBuilder.nodeOf(child("cloud.svs")).getSuffix());
        assertSame(pendingRow, child("pending.svs"));
        assertFalse(panel.getCancelResolveButton().isVisible());
        assertEquals("Resolved 'cloud.svs': 1 tensor|false", heard.get(heard.size() - 1));
    }

    @Test
    public void declinedConsentResolvesNothingButPendingAndFailedNeverAsk() throws Exception {
        consent = false;
        edt(() -> panel.resolveSource("cloud"));
        assertEquals(1, asked.size());
        assertEquals(0, fake.resolveCalls.get());
        fake.release.countDown();
        edt(() -> panel.resolveSource("pending"));
        edt(() -> panel.resolveSource("failed"));
        await(() -> fake.resolveCalls.get() == 2);
        assertEquals(1, asked.size());
    }

    @Test
    public void cancelStopsTheResolveAndLeavesTheRowUnresolved() throws Exception {
        edt(() -> panel.resolveSource("cloud"));
        assertTrue(fake.started.await(10, TimeUnit.SECONDS));
        edt(() -> panel.getCancelResolveButton().doClick());
        await(() -> status().startsWith("Cancelled"));
        assertTrue(fake.sawCancel);
        assertEquals(TreeModelBuilder.CLOUD_GLYPH, TreeModelBuilder.nodeOf(child("cloud.svs")).getSuffix());
        assertFalse(panel.getCancelResolveButton().isVisible());
        assertTrue(heard.get(heard.size() - 1).endsWith("|false"));
    }

    @Test
    public void aFailedResolveSaysWhy() throws Exception {
        fake.failWith = "disk full";
        edt(() -> panel.resolveSource("failed"));
        fake.release.countDown();
        await(() -> status().startsWith("Resolve failed"));
        assertTrue(status(), status().contains("disk full"));
        assertEquals("[failed]", TreeModelBuilder.nodeOf(child("failed.svs")).getSuffix());
        assertFalse(panel.getCancelResolveButton().isVisible());
    }

    @Test
    public void anOutcomeAfterReconnectingIsDropped() throws Exception {
        edt(() -> panel.resolveSource("pending"));
        assertTrue(fake.started.await(10, TimeUnit.SECONDS));
        edt(panel::connect); // a new connection: the running resolve is cancelled and forgotten
        await(() -> fake.sawCancel);
        Thread.sleep(300);
        edt(() -> {
            assertFalse(status(), status().startsWith("Cancelled"));
            assertFalse(status(), status().startsWith("Resolve"));
            assertFalse(panel.getCancelResolveButton().isVisible());
        });
        assertEquals(" |false", heard.get(heard.size() - 1));
    }

    @Test
    public void menuOffersResolveForEveryUnresolvedStateAndCancelWhileRunning() throws Exception {
        String[][] expect = {{"cloud.svs", "Resolve (downloads file)…"}, {"pending.svs", "Load now"},
            {"failed.svs", "Retry loading"}};
        edt(() -> {
            for (String[] e : expect) {
                JPopupMenu m = panel.menuFor(TreeModelBuilder.nodeOf(child(e[0])));
                assertEquals(1, m.getComponentCount());
                assertEquals(e[1], ((JMenuItem) m.getComponent(0)).getText());
            }
        });
        edt(() -> ((JMenuItem) panel.menuFor(TreeModelBuilder.nodeOf(child("cloud.svs"))).getComponent(0)).doClick());
        assertTrue(fake.started.await(10, TimeUnit.SECONDS));
        edt(() -> {
            JMenuItem running = (JMenuItem) panel.menuFor(TreeModelBuilder.nodeOf(child("cloud.svs"))).getComponent(0);
            assertEquals("Cancel resolve", running.getText());
            running.doClick();
        });
        await(() -> status().startsWith("Cancelled"));
    }

    @Test
    public void doubleClickOnAnUnresolvedSourceResolvesIt() throws Exception {
        edt(() -> {
            javax.swing.JTree t = panel.getTree();
            t.setSize(400, 300);
            java.awt.Rectangle r = t.getPathBounds(new TreePath(child("pending.svs").getPath()));
            assertNotNull(r);
            for (java.awt.event.MouseListener ml : t.getMouseListeners()) {
                if (!ml.getClass().getName().contains("javax.swing")) {
                    ml.mouseClicked(new java.awt.event.MouseEvent(t, java.awt.event.MouseEvent.MOUSE_CLICKED,
                            System.currentTimeMillis(), 0, r.x + 5, r.y + r.height / 2, 2, false,
                            java.awt.event.MouseEvent.BUTTON1));
                }
            }
        });
        assertTrue(fake.started.await(10, TimeUnit.SECONDS));
        assertEquals(1, fake.resolveCalls.get());
        assertTrue(asked.isEmpty()); // pending: no download, no question
    }
}
