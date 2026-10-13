package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.prefs.Preferences;
import org.junit.Test;

/** The Swing-free parts of the window polish: status text, empty-tree message, remembered bounds. */
public class LayoutPolishTest {

    @Test
    public void connectedTextIsShortAndSaysLocalOrRemote() {
        assertEquals("Connected - local", ConnectionStatus.connectedText("grpc://127.0.0.1:8815"));
        assertEquals("Connected - local", ConnectionStatus.connectedText("grpc://localhost:1"));
        assertEquals("Connected - remote (TLS)", ConnectionStatus.connectedText("grpc+tls://example.com:9"));
        assertEquals("Connected - remote (plaintext)", ConnectionStatus.connectedText("grpc://10.0.0.5:9"));
        assertEquals("Connected", ConnectionStatus.connectedText(null));
        assertEquals("Connected to 127.0.0.1:8815 (plaintext)", ConnectionStatus.connectedDetail("127.0.0.1:8815 (plaintext)"));
    }

    @Test
    public void emptyTreeSaysWhy() {
        assertEquals("Not connected", EmptyState.message(false, false, false, false, false));
        assertEquals("Connecting…", EmptyState.message(false, true, false, false, false));
        assertEquals("No matching sources", EmptyState.message(true, false, false, true, true));
        assertEquals("No sources - drop a folder here", EmptyState.message(true, false, false, false, true));
        assertEquals("Loading\u2026", EmptyState.message(true, false, true, false, true));
        assertEquals("No sources", EmptyState.message(true, false, false, false, false));
    }

    @Test
    public void boundsRoundTripAndBadValuesAreIgnored() {
        Rectangle r = new Rectangle(10, -5, 560, 720);
        assertEquals(r, WindowPrefs.parse(WindowPrefs.format(r)));
        assertNull(WindowPrefs.parse(null));
        assertNull(WindowPrefs.parse("1,2,3"));
        assertNull(WindowPrefs.parse("a,b,c,d"));
        assertNull(WindowPrefs.parse("1,2,0,5"));
    }

    @Test
    public void savedBoundsAreFittedToTheScreenTheyOverlap() {
        Rectangle[] screens = {new Rectangle(0, 0, 1920, 1040), new Rectangle(1920, 0, 1280, 1024)};
        Dimension min = new Dimension(400, 460);
        assertEquals(new Rectangle(100, 100, 600, 700), WindowPrefs.usable(new Rectangle(100, 100, 600, 700), min, screens));
        assertEquals(new Rectangle(100, 100, 400, 460), WindowPrefs.usable(new Rectangle(100, 100, 50, 50), min, screens));
        assertNull(WindowPrefs.usable(new Rectangle(5000, 100, 600, 700), min, screens)); // its monitor is gone
        assertNull(WindowPrefs.usable(null, min, screens));
        // straddling two screens: fitted to the one it overlaps more, moved back in, so the title bar is reachable
        assertEquals(new Rectangle(1920, 324, 600, 700), WindowPrefs.usable(new Rectangle(1800, 900, 600, 700), min, screens));
        // taller than the screen: shrunk to it
        assertEquals(new Rectangle(100, 0, 600, 1040), WindowPrefs.usable(new Rectangle(100, -50, 600, 2000), min, screens));
    }

    @Test
    public void wrappedLinesAreCountedAtWordBoundaries() {
        java.util.function.ToIntFunction<String> chars = String::length;
        assertEquals(1, TextWrap.lines("", 10, chars));
        assertEquals(1, TextWrap.lines("one two", 10, chars));
        assertEquals(2, TextWrap.lines("one two three", 10, chars));
        assertEquals(3, TextWrap.lines("aaaaaaaaaaaaaaaaaaaaaaaaa", 10, chars)); // one word wider than the line
        assertEquals(1, TextWrap.lines("anything", 0, chars)); // no width yet
    }

    private static TensorBrowserPanel.Connection connection(String url) {
        return new TensorBrowserPanel.Connection() {
            @Override public CatalogClient catalog() { return new TensorBrowserPanelTest.Fake(); }
            @Override public biopb.tensor.TensorFlightClient flight() { return null; }
            @Override public String url() { return url; }
            @Override public void close() { }
        };
    }

    private static void await(java.util.function.BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(20);
        }
    }

    @Test
    public void connectionLineSurvivesMessagesAndAFailedReconnect() throws Exception {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        TensorBrowserPanel p = new TensorBrowserPanel((u, t) -> {
            if (n.incrementAndGet() > 1) {
                throw new java.io.IOException("boom");
            }
            return connection("grpc://127.0.0.1:8815");
        }, "127.0.0.1:8815", "");
        try {
            javax.swing.SwingUtilities.invokeAndWait(p::connect);
            await(() -> p.getConnection() != null);
            ConnectionLabel line = p.getConnectionLabel();
            assertEquals(ConnectionStatus.State.CONNECTED, line.getState());
            assertEquals("Connected - local", line.getText());
            assertEquals("Connected to 127.0.0.1:8815 (plaintext)", line.getToolTipText());
            javax.swing.SwingUtilities.invokeAndWait(() -> { // a transient message does not touch the line
                p.getAddressField().setText("h:99999");
                p.connect();
            });
            assertTrue(p.getStatusLabel().isVisible());
            assertTrue(p.getStatusLabel().getText(), p.getStatusLabel().getText().contains("port"));
            assertEquals(ConnectionStatus.State.CONNECTED, line.getState());
            assertEquals("Connected to 127.0.0.1:8815 (plaintext)", line.getToolTipText());
            javax.swing.SwingUtilities.invokeAndWait(() -> { // a failed re-connect keeps the old connection, green
                p.getAddressField().setText("127.0.0.1:2");
                p.connect();
            });
            await(() -> n.get() == 2 && p.getConnectButton().isEnabled() && p.getStatusLabel().getText().contains("still connected"));
            assertEquals(ConnectionStatus.State.CONNECTED, line.getState());
            assertEquals("Connected - local", line.getText());
            assertEquals("Connected to 127.0.0.1:8815 (plaintext)", line.getToolTipText());
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(p::dispose);
        }
    }

    @Test
    public void failedFirstConnectIsRedWithTheReasonAsDetail() throws Exception {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        TensorBrowserPanel p = new TensorBrowserPanel((u, t) -> { throw new java.io.IOException("boom"); }, "h:1", "");
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                p.setErrorDialog(m -> { });
                p.connect();
            });
            await(() -> p.getConnectionLabel().getState() == ConnectionStatus.State.ERROR && p.getConnectButton().isEnabled());
            assertEquals("Connection failed", p.getConnectionLabel().getText());
            assertEquals("boom", p.getConnectionLabel().getToolTipText());
            assertEquals("Connection failed: boom", p.getStatusLabel().getText());
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(p::dispose);
        }
    }

    @Test
    public void prefsSaveAndLoadThroughANode() throws Exception {
        Preferences node = Preferences.userRoot().node("biopb-fiji-test-" + System.nanoTime());
        try {
            WindowPrefs p = new WindowPrefs(node);
            assertNull(p.load());
            p.save(new Rectangle(1, 2, 3, 4));
            assertEquals(new Rectangle(1, 2, 3, 4), p.load());
        } finally {
            node.removeNode();
        }
    }

    @Test
    public void connectButtonStaysInsideThePanelAtTheMinimumWidth() throws Exception {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        TensorBrowserPanel p = new TensorBrowserPanel((u, t) -> { throw new java.io.IOException("x"); }, "localhost:8815", "tok");
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                Dimension min = p.getMinimumSize();
                p.setSize(min.width, 600);
                layout(p);
                Rectangle b = javax.swing.SwingUtilities.convertRectangle(p.getConnectButton().getParent(), p.getConnectButton().getBounds(), p);
                assertTrue(b.toString(), b.width > 0 && b.x >= 0 && b.x + b.width <= p.getWidth());
                assertTrue(p.getAddressField().getWidth() > 0);
                assertEquals("Not connected", p.getConnectionLabel().getText());
            });
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(p::dispose);
        }
    }

    private static void layout(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof java.awt.Container) {
                layout((java.awt.Container) k);
            }
        }
    }
}
