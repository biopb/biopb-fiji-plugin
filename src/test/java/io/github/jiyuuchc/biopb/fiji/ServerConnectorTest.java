package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import biopb.tensor.Connection;
import biopb.tensor.TensorFlightClient;
import biopb.tensor.TlsPinStore;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.Assume;
import org.junit.Test;

/** Address/scheme handling, the connector against a fake SDK connection, and the panel's failure UX. */
public class ServerConnectorTest {
    private static final String SECRET = "s3cr3t-token";

    /** A scripted SDK connection: records what it was asked, answers with {@link #ok}/{@link #message}. */
    static final class FakeDial implements ServerConnector.Dial {
        final List<String> calls = new ArrayList<>();
        Duration timeout;
        boolean ok;
        String message = "";
        String url;
        boolean closed;

        @Override public boolean connect(String u, String token, Duration t) {
            calls.add(u + "|" + token);
            timeout = t;
            return ok;
        }
        @Override public TensorFlightClient client() { return null; }
        @Override public String url() { return url; }
        @Override public String lastMessage() { return message; }
        @Override public void close() { closed = true; }
    }

    @Test
    public void typedAddressesBecomeUrls() {
        assertEquals("grpc://h:1", TensorBrowserPanel.toUrl("h:1"));
        assertEquals("grpc://h:8815", TensorBrowserPanel.toUrl(" h "));
        assertEquals("grpc://[::1]:9", TensorBrowserPanel.toUrl("[::1]:9"));
        assertEquals("grpc://h:5", TensorBrowserPanel.toUrl("grpc://h:5/path"));
        assertEquals("grpc+tcp://h:5", TensorBrowserPanel.toUrl("grpc+tcp://h:5"));
        assertEquals("grpc+tls://h:2", TensorBrowserPanel.toUrl("GRPC+TLS://h:2/x"));
        assertEquals("grpcs://h:3", TensorBrowserPanel.toUrl("grpcs://h:3"));
        assertEquals("grpc+tls://[::1]:8815", TensorBrowserPanel.toUrl("grpc+tls://[::1]:8815"));
        assertEquals("grpc://[::1]:8815", TensorBrowserPanel.toUrl("::1"));
        assertEquals("grpc+tls://h:1", TensorBrowserPanel.toUrl("GRPC+TLS://h:1"));
        assertEquals("grpc://h:1", TensorBrowserPanel.toUrl("h:1/a/b?x=1#f"));
        assertEquals("grpc://h:1", TensorBrowserPanel.toUrl("h:1?x=1"));
        for (String bad : new String[] {"http://h:1", "ftp://h", "grpc+tls://h:x", "user@h:1", "grpc://u:p@h:1", "HTTP://h:1"}) {
            try {
                TensorBrowserPanel.toUrl(bad);
                fail(bad);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().contains(SECRET));
            }
        }
    }

    @Test
    public void unsupportedSchemeIsReportedBeforeAnythingElse() {
        try {
            TensorBrowserPanel.toUrl("http://user@h:x");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Unsupported scheme"));
        }
    }

    @Test
    public void loopbackHosts() {
        for (String u : new String[] {"grpc://localhost:1", "grpc://LOCALHOST:1", "grpc://127.0.0.1:1", "grpc://127.1.2.3:1", "grpc://[::1]:1", "grpc://localhost.:1",
            "grpc+tls://[0:0:0:0:0:0:0:1]:8815", "::1", "::ffff:127.0.0.1", "grpc://[::ffff:127.0.0.1]:3", "127.0.0.1"}) {
            assertTrue(u, TensorBrowserPanel.isLoopback(u));
        }
        for (String u : new String[] {"grpc://example.org:1", "grpc://10.0.0.1:1", "grpc://127.example.org:1x", "grpc://[::2]:1", "127.999.1.1:5", "grpc://ip6-localhost:1", "ip6-localhost", "grpc://localhost.example.org:1"}) {
            assertFalse(u, TensorBrowserPanel.isLoopback(u));
        }
    }

    @Test
    public void theSchemeIsNamedInTheStatus() {
        assertEquals("h:1 (plaintext)", TensorBrowserPanel.describe("grpc://h:1"));
        assertEquals("h:1 (plaintext)", TensorBrowserPanel.describe("grpc+tcp://h:1"));
        assertEquals("h:1 (TLS)", TensorBrowserPanel.describe("grpc+tls://h:1"));
        assertEquals("h:1 (TLS)", TensorBrowserPanel.describe("grpcs://h:1"));
        assertEquals("tok", TensorBrowserPanel.defaultToken(k -> " tok "));
        assertEquals("", TensorBrowserPanel.defaultToken(k -> null));
    }

    @Test
    public void typedUrlGetsItsTokenAndTheShortTimeout() throws Exception {
        FakeDial d = new FakeDial();
        d.ok = true;
        d.url = "grpc+tls://h:1";
        TensorBrowserPanel.Connection c = new ServerConnector(() -> d).connect("grpc+tls://h:1", SECRET);
        assertEquals("grpc+tls://h:1|" + SECRET, d.calls.get(0));
        assertEquals(ServerConnector.TYPED_TIMEOUT, d.timeout);
        assertEquals("grpc+tls://h:1", c.url());
        assertFalse(d.closed);
        c.close(); // closing the panel's connection closes the SDK connection
        assertTrue(d.closed);
    }

    @Test
    public void emptyAddressDiscoversWithTheSdksLongTimeout() throws Exception {
        FakeDial d = new FakeDial();
        d.ok = true;
        d.url = "grpc://localhost:8815";
        new ServerConnector(() -> d).connect(null, null);
        assertEquals("null|null", d.calls.get(0));
        assertEquals(Connection.START_TIMEOUT, d.timeout);
    }

    @Test
    public void failureCarriesTheSdkMessageVerbatimAndClosesTheDial() {
        FakeDial d = new FakeDial();
        d.message = "The certificate of h:1 changed.\nTo trust it, remove the entry for h:1.";
        try {
            new ServerConnector(() -> d).connect("grpc+tls://h:1", SECRET);
            fail();
        } catch (IOException e) {
            assertEquals(d.message, e.getMessage());
        }
        assertTrue(d.closed);
    }

    @Test
    public void unreachablePlaintextServerReportsTheSdkMessageWithoutTheToken() {
        Connection sdk = new Connection(TlsPinStore.inMemory());
        try {
            // port 1 is closed; a typed URL is not retried, so this is quick
            ServerConnector.over(() -> sdk).connect("grpc://127.0.0.1:1", SECRET);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("127.0.0.1:1"));
            assertFalse(e.getMessage().contains(SECRET));
        }
    }

    // --- the panel ---------------------------------------------------------------

    private static void await(BooleanSupplier c) throws Exception {
        for (int i = 0; i < 200; i++) {
            boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = c.getAsBoolean());
            if (ok[0]) {
                return;
            }
            Thread.sleep(50);
        }
        fail("timed out");
    }

    @Test
    public void panelShowsAFailureInTheStatusLineAndADialogButNeverTheToken() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        List<String> asked = new CopyOnWriteArrayList<>();
        List<String> dialogs = new CopyOnWriteArrayList<>();
        String message = "Authentication failed: the tensor server at grpc://h:1 rejected the token.";
        TensorBrowserPanel p = new TensorBrowserPanel((u, t) -> {
            asked.add(u + "|" + t);
            throw new IOException(message);
        }, "grpc+tls://h:1", SECRET);
        try {
            SwingUtilities.invokeAndWait(() -> {
                p.setErrorDialog(dialogs::add);
                p.connect();
            });
            await(() -> !dialogs.isEmpty());
            assertEquals("grpc+tls://h:1|" + SECRET, asked.get(0)); // the token goes to the connector ...
            assertEquals(message, dialogs.get(0));
            String status = p.getStatusLabel().getText();
            assertTrue(status, status.contains(message));
            assertFalse(status.contains(SECRET)); // ... and nowhere else
            assertFalse(dialogs.get(0).contains(SECRET));
            assertTrue(p.getConnectButton().isEnabled());
        } finally {
            SwingUtilities.invokeAndWait(p::dispose);
        }
    }

    @Test
    public void panelNamesTheSchemeOnSuccessAndEmptyAddressDiscovers() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        List<String> asked = new CopyOnWriteArrayList<>();
        TensorBrowserPanel p = new TensorBrowserPanel((u, t) -> {
            asked.add(u + "|" + t);
            return new TensorBrowserPanel.Connection() {
                @Override public CatalogClient catalog() { return new TensorBrowserPanelTest.Fake(); }
                @Override public TensorFlightClient flight() { return null; }
                @Override public String url() { return u == null ? "grpc+tls://found:9" : u; }
                @Override public void close() { }
            };
        }, "grpc+tls://h:1", "");
        try {
            SwingUtilities.invokeAndWait(p::connect);
            await(() -> p.getConnection() != null);
            assertTrue(p.getStatusLabel().getText(), p.getStatusLabel().getText().startsWith("Connected to h:1 (TLS)"));
            assertEquals("grpc+tls://h:1|null", asked.get(0)); // an empty token field is no token
            SwingUtilities.invokeAndWait(() -> {
                p.getAddressField().setText("");
                p.connect();
            });
            await(() -> asked.size() == 2 && p.getConnectButton().isEnabled());
            assertEquals("null|null", asked.get(1));
            assertTrue(p.getStatusLabel().getText(), p.getStatusLabel().getText().startsWith("Connected to found:9 (TLS)"));
        } finally {
            SwingUtilities.invokeAndWait(p::dispose);
        }
    }

    @Test
    public void tokenIsNotSentInPlaintextToARemoteHost() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        List<String> asked = new CopyOnWriteArrayList<>();
        List<String> dialogs = new CopyOnWriteArrayList<>();
        TensorBrowserPanel p = new TensorBrowserPanel((u, t) -> {
            asked.add(u + "|" + t);
            throw new IOException("down");
        }, "example.org:1", SECRET);
        try {
            SwingUtilities.invokeAndWait(() -> {
                p.setErrorDialog(dialogs::add);
                p.connect();
            });
            await(() -> !dialogs.isEmpty());
            assertTrue(asked.isEmpty());
            assertTrue(dialogs.get(0), dialogs.get(0).contains("grpc+tls://example.org:1"));
            assertFalse(dialogs.get(0).contains(SECRET));
            assertFalse(p.getStatusLabel().getText().contains(SECRET));
            // loopback plaintext, and TLS to anywhere, are fine
            SwingUtilities.invokeAndWait(() -> {
                p.getAddressField().setText("127.0.0.1:1");
                p.connect();
            });
            await(() -> asked.size() == 1 && p.getConnectButton().isEnabled());
            assertEquals("grpc://127.0.0.1:1|" + SECRET, asked.get(0));
            SwingUtilities.invokeAndWait(() -> {
                p.getAddressField().setText("grpc+tls://example.org:1");
                p.connect();
            });
            await(() -> asked.size() == 2 && p.getConnectButton().isEnabled());
        } finally {
            SwingUtilities.invokeAndWait(p::dispose);
        }
    }

    @Test
    public void tokenFieldIsOffForDiscoveryAndNoDialogAfterDispose() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        List<String> dialogs = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        TensorBrowserPanel p = new TensorBrowserPanel((u, t) -> {
            try {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            throw new IOException("late failure");
        }, "grpc+tls://h:1", SECRET);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(p.getTokenField().isEnabled());
            p.getAddressField().setText("");
            assertFalse(p.getTokenField().isEnabled());
            p.getAddressField().setText("grpc+tls://h:1");
            assertTrue(p.getTokenField().isEnabled());
            p.setErrorDialog(dialogs::add);
            p.connect();
            p.dispose(); // the panel is gone before the connect fails
        });
        gate.countDown();
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(dialogs.isEmpty());
    }

    @Test
    public void aSecondConnectWhileADialogIsUpDoesNotStackAnother() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        List<String> dialogs = new CopyOnWriteArrayList<>();
        TensorBrowserPanel p = new TensorBrowserPanel((u, t) -> { throw new IOException("down"); }, "example.org:1", SECRET);
        try {
            SwingUtilities.invokeAndWait(() -> p.setErrorDialog(m -> {
                dialogs.add(m);
                p.connect(); // a click while the dialog is up; its own dialog is queued ...
                java.awt.SecondaryLoop loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
                SwingUtilities.invokeLater(loop::exit); // ... and runs inside this nested loop, as under a modal dialog
                loop.enter();
            }));
            SwingUtilities.invokeAndWait(p::connect);
            await(() -> !dialogs.isEmpty());
            Thread.sleep(500);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(1, dialogs.size());
        } finally {
            SwingUtilities.invokeAndWait(p::dispose);
        }
    }
}
