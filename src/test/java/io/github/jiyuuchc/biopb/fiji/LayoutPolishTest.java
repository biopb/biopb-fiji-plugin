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
        assertEquals("Not connected", EmptyState.message(false, false, false, false));
        assertEquals("Connecting…", EmptyState.message(false, true, false, false));
        assertEquals("No matching sources", EmptyState.message(true, false, true, true));
        assertEquals("No sources - drop a folder here", EmptyState.message(true, false, false, true));
        assertEquals("No sources", EmptyState.message(true, false, false, false));
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
    public void savedBoundsMustBeOnAScreenAndNotBelowTheMinimum() {
        Rectangle[] screens = {new Rectangle(0, 0, 1920, 1080)};
        Dimension min = new Dimension(400, 460);
        assertEquals(new Rectangle(100, 100, 600, 700), WindowPrefs.usable(new Rectangle(100, 100, 600, 700), min, screens));
        assertEquals(new Rectangle(100, 100, 400, 460), WindowPrefs.usable(new Rectangle(100, 100, 50, 50), min, screens));
        assertNull(WindowPrefs.usable(new Rectangle(3000, 100, 600, 700), min, screens)); // its monitor is gone
        assertNull(WindowPrefs.usable(null, min, screens));
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
                assertEquals("Not connected", p.getStatusLabel().getText());
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
