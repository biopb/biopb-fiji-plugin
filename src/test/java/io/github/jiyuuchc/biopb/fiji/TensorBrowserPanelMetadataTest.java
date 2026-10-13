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
import java.util.function.BooleanSupplier;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** The metadata window and the pane under the tree, against a fake server. Needs a display (xvfb in CI). */
public class TensorBrowserPanelMetadataTest {

    /** Two sources at the top: "one" (one tensor) and "many" (two). Metadata can be held, failed or left unsupported. */
    static final class Fake implements CatalogClient {
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile CountDownLatch gate;
        volatile String failWith;
        volatile boolean unsupported;

        @Override
        public Level catalogChildren(String key, int limit) {
            return new Level("", Collections.<Folder>emptyList(),
                    Arrays.asList(new Source("one.zarr", "one"), new Source("many.zarr", "many")), false);
        }

        @Override
        public List<Map<String, Object>> query(String sql) {
            return new ArrayList<>(Arrays.asList(
                    TreeModelBuilderTest.source("one", "/d/one.zarr", TreeModelBuilderTest.tensor("one/0", "uint16", 1, 64, 32)),
                    TreeModelBuilderTest.source("many", "/d/many.zarr", TreeModelBuilderTest.tensor("many/0", "uint8", 4, 4),
                            TreeModelBuilderTest.tensor("many/1", "uint8", 2, 2))));
        }

        @Override
        public Map<String, Object> getSourceMetadata(String sourceId) throws IOException {
            threads.add(Thread.currentThread().getName());
            asked.add(sourceId);
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (unsupported) {
                throw new UnsupportedOperationException();
            }
            if (failWith != null) {
                throw new IOException(failWith);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instrument", "scope-9");
            m.put("blank", "");
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("pixel_size", Arrays.asList(0.5, 0.5));
            m.put("acq", n);
            return m;
        }

        @Override public Map<String, Object> healthCheck() { return new LinkedHashMap<>(); }
        @Override public String sourceRowColumns() { return null; }
    }

    private Fake fake;
    private TensorBrowserPanel panel;

    @Before
    public void display() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        fake = new Fake();
        panel = new TensorBrowserPanel((host, port) -> new TensorBrowserPanel.Connection() {
            @Override public CatalogClient catalog() { return fake; }
            @Override public biopb.tensor.TensorFlightClient flight() { return null; }
            @Override public void close() { }
        }, "h:1");
        edt(panel::connect);
        await(() -> child("one.zarr") != null && child("many.zarr") != null);
    }

    @After
    public void cleanup() throws Exception {
        if (panel != null) {
            edt(panel::dispose);
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
            TreeModelBuilder.Node n = TreeModelBuilder.nodeOf(root.getChildAt(i));
            if (n != null && n.getText().equals(text)) {
                return (DefaultMutableTreeNode) root.getChildAt(i);
            }
        }
        return null;
    }

    private static JMenuItem metadataItem(JPopupMenu menu) {
        for (java.awt.Component c : menu.getComponents()) {
            if (c instanceof JMenuItem && ((JMenuItem) c).getText().startsWith("Metadata")) {
                return (JMenuItem) c;
            }
        }
        return null;
    }

    private MetadataDialog openFor(String row) throws Exception {
        edt(() -> {
            JMenuItem item = metadataItem(panel.menuFor(TreeModelBuilder.nodeOf(child(row))));
            assertNotNull("no Metadata item", item);
            item.doClick();
        });
        assertEquals(1, panel.getMetadataDialogs().size());
        return panel.getMetadataDialogs().iterator().next();
    }

    @Test
    public void menuOffersMetadataOnSourcesAndTensorsButNotFolders() throws Exception {
        edt(() -> {
            assertNotNull(metadataItem(panel.menuFor(TreeModelBuilder.nodeOf(child("one.zarr")))));
            assertNotNull(metadataItem(panel.menuFor(TreeModelBuilder.nodeOf(child("many.zarr")))));
            assertNotNull(metadataItem(panel.menuFor(TreeModelBuilder.nodeOf(child("many.zarr").getChildAt(0)))));
        });
    }

    @Test
    public void dialogOpensAtOnceReadsOffTheEdtAndShowsFilteredMetadata() throws Exception {
        fake.gate = new CountDownLatch(1);
        MetadataDialog d = openFor("one.zarr");
        assertTrue(d.isVisible());
        assertFalse(d.isModal());
        assertEquals("Loading…", d.bodyText()); // up before the server answered
        assertEquals("one", d.sourceRow().value());
        assertEquals("one/0", d.tensorRow().value());
        fake.gate.countDown();
        await(() -> d.bodyText().contains("scope-9"));
        assertFalse(d.bodyText(), d.bodyText().contains("blank"));
        assertTrue(d.bodyText(), d.bodyText().contains("\"pixel_size\": [\n      0.5,"));
        assertEquals(Arrays.asList("one"), fake.asked);
        for (String t : fake.threads) {
            assertFalse("server call on the EDT: " + t, t.startsWith("AWT-EventQueue"));
        }
    }

    @Test
    public void tensorRowOpensItsSourcesMetadata() throws Exception {
        edt(() -> metadataItem(panel.menuFor(TreeModelBuilder.nodeOf(child("many.zarr").getChildAt(0)))).doClick());
        await(() -> fake.asked.equals(Arrays.asList("many")));
        MetadataDialog d = panel.getMetadataDialogs().iterator().next();
        assertEquals("many/0", d.tensorRow().value());
    }

    @Test
    public void failureAndUnsupportedServerShowAnErrorInTheWindow() throws Exception {
        fake.failWith = "boom";
        MetadataDialog d = openFor("one.zarr");
        await(() -> d.bodyText().startsWith("Could not read the metadata"));
        assertTrue(d.bodyText(), d.bodyText().contains("boom"));
        edt(d::dispose);
        fake.failWith = null;
        fake.unsupported = true;
        MetadataDialog d2 = openFor("one.zarr");
        await(() -> d2.bodyText().contains("does not provide source metadata"));
    }

    @Test
    public void reconnectingClosesTheWindowAndDropsItsAnswer() throws Exception {
        fake.gate = new CountDownLatch(1);
        MetadataDialog d = openFor("one.zarr");
        edt(panel::connect); // a new connection replaces the one the window was opened on
        await(() -> !d.isDisplayable());
        fake.gate.countDown();
        Thread.sleep(300);
        assertFalse(d.isDisplayable());
        assertTrue(panel.getMetadataDialogs().isEmpty());
    }

    @Test
    public void disposingThePanelClosesTheWindow() throws Exception {
        fake.gate = new CountDownLatch(1);
        MetadataDialog d = openFor("one.zarr");
        edt(panel::dispose);
        assertFalse(d.isDisplayable());
        fake.gate.countDown();
        Thread.sleep(300); // the late answer lands on a disposed dialog: nothing throws, nothing reopens
        assertFalse(d.isDisplayable());
    }

    @Test
    public void paneFollowsTheSelectedTensor() throws Exception {
        MetadataPane pane = panel.getInfoPane();
        assertFalse(pane.isVisible());
        edt(() -> panel.getTree().setSelectionPath(new TreePath(child("one.zarr").getPath())));
        assertTrue(pane.isVisible());
        assertEquals("one", pane.sourceRow().value());
        assertEquals("one/0", pane.tensorRow().value());
        assertEquals("Shape: 1×64×32   Dtype: uint16   Dims: N/A", pane.linesText());
        edt(() -> panel.getTree().setSelectionPath(new TreePath(child("many.zarr").getPath())));
        assertFalse(pane.isVisible()); // several tensors: pick one
        edt(() -> panel.getTree().expandPath(new TreePath(child("many.zarr").getPath())));
        edt(() -> panel.getTree().setSelectionPath(new TreePath(((DefaultMutableTreeNode) child("many.zarr").getChildAt(1)).getPath())));
        assertTrue(pane.isVisible());
        assertEquals("many", pane.sourceRow().value());
        assertEquals("many/1", pane.tensorRow().value());
        edt(() -> panel.getTree().clearSelection());
        assertFalse(pane.isVisible());
    }
}
