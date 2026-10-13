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
        for (String bad : new String[] {"http://h:1", "ftp://h", "grpc+tls://h:x"}) {
            try {
                TensorBrowserPanel.toUrl(bad);
                fail(bad);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().contains(SECRET));
            }
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
        }, "h:1", SECRET);
        try {
            SwingUtilities.invokeAndWait(() -> {
                p.setErrorDialog(dialogs::add);
                p.connect();
            });
            await(() -> !dialogs.isEmpty());
            assertEquals("grpc://h:1|" + SECRET, asked.get(0)); // the token goes to the connector ...
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
}
