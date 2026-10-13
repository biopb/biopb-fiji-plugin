package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Which rows offer "Remove source", and the Add row's hint, in the panel. Needs a display. */
public class TensorBrowserPanelEditTest {

    /** Top: folders "scans" (dropped) and "plain", and sources x.tif (dropped file) and y.tif. */
    static final class Fake implements CatalogClient {
        @Override public Level catalogChildren(String key, int limit) {
            return new Level("", Arrays.asList(new Folder("scans", "scans"), new Folder("plain", "plain")),
                    Arrays.asList(new Source("x.tif", "x"), new Source("y.tif", "y")), false);
        }

        private static Map<String, Object> row(String id, String url) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source_id", id);
            row.put("source_url", url);
            row.put("is_resolved", true);
            row.put("tensors", Arrays.asList(TreeModelBuilderTest.tensor(id + "/0", "uint8", 4, 4)));
            return row;
        }

        @Override public List<Map<String, Object>> query(String sql) {
            if (sql.contains("regexp_extract")) {
                return Arrays.asList(Collections.<String, Object>singletonMap("root", "dnd://scans"));
            }
            return new ArrayList<>(Arrays.asList(row("x", "dnd://x.tif"), row("y", "/data/y.tif")));
        }

        @Override public Map<String, Object> healthCheck() { return new LinkedHashMap<>(); }
        @Override public String sourceRowColumns() { return null; }
    }

    private TensorBrowserPanel panel;
    private volatile String url = "grpc://localhost:8815";

    @Before
    public void display() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        panel = new TensorBrowserPanel((host, port) -> new TensorBrowserPanel.Connection() {
            @Override public CatalogClient catalog() { return new Fake(); }
            @Override public biopb.tensor.TensorFlightClient flight() { return null; }
            @Override public String url() { return url; }
            @Override public void close() { }
        }, "h:1");
        edt(() -> {
            assertEquals(DropPolicy.NOT_CONNECTED, panel.getEditorForTest().hintText());
            panel.connect();
        });
        await(() -> panel.getTree().getRowCount() >= 4);
    }

    @After
    public void cleanup() throws Exception {
        if (panel != null) {
            edt(panel::dispose);
        }
    }

    private static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }

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

    private String removeRoot(String name) throws Exception {
        String[] r = new String[1];
        edt(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.getTree().getModel().getRoot();
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode c = (DefaultMutableTreeNode) root.getChildAt(i);
                if (TreeModelBuilder.nodeOf(c).getText().equals(name)) {
                    r[0] = panel.removeRootOf(new TreePath(c.getPath()));
                    return;
                }
            }
            throw new AssertionError("no row " + name);
        });
        return r[0];
    }

    @Test
    public void onlyDroppedTopLevelRowsCanBeRemoved() throws Exception {
        assertEquals("dnd://scans", removeRoot("scans"));
        assertNull(removeRoot("plain"));
        assertEquals("dnd://x.tif", removeRoot("x.tif"));
        assertNull(removeRoot("y.tif"));
    }

    @Test
    public void aLocalConnectionInvitesDrops() throws Exception {
        edt(() -> {
            assertEquals(DropPolicy.READY, panel.getEditorForTest().hintText());
            assertTrue(panel.getEditorForTest().addButtonForTest().isEnabled());
        });
    }

    @Test
    public void aRemoteConnectionRefusesAndSaysWhy() throws Exception {
        url = "grpc://example.com:8815";
        edt(panel::connect);
        await(() -> DropPolicy.REMOTE.equals(panel.getEditorForTest().hintText()));
        edt(() -> assertFalse(panel.getEditorForTest().addButtonForTest().isEnabled()));
    }
}
