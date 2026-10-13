package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.TensorFlightClient;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The real {@link TensorBrowserPanel.ClientFactory}: dials through the SDK's
 * {@link biopb.tensor.Connection}, which resolves TLS trust (system roots, a CA or fingerprint from
 * the environment, or a trust-on-first-use pin in the pin file shared with the Python clients) and
 * words each failure for the user. A {@code null} url means discovery ({@code BIOPB_TENSOR_URL},
 * then the local control plane).
 */
public final class ServerConnector implements TensorBrowserPanel.ClientFactory {
    /** How long a typed address is given; discovery gets the SDK's longer {@code START_TIMEOUT}. */
    static final Duration TYPED_TIMEOUT = Duration.ofSeconds(20);

    /** The part of the SDK connection this class uses (a seam: the SDK class is final). */
    interface Dial extends AutoCloseable {
        boolean connect(String url, String token, Duration timeout);
        TensorFlightClient client();
        String url();
        String lastMessage();
        @Override void close();
    }

    private final Supplier<Dial> dials;

    /** Dials with a fresh SDK connection using the shared pin file. */
    public ServerConnector() {
        this(() -> sdk(new biopb.tensor.Connection()));
    }

    /** Dials with the SDK connections {@code sdk} supplies (e.g. one over an in-memory pin store). */
    public static ServerConnector over(Supplier<biopb.tensor.Connection> sdk) {
        return new ServerConnector(() -> sdk(sdk.get()));
    }

    ServerConnector(Supplier<Dial> dials) {
        this.dials = dials;
    }

    private static Dial sdk(biopb.tensor.Connection c) {
        return new Dial() {
            @Override public boolean connect(String url, String token, Duration t) { return c.connect(url, token, t); }
            @Override public TensorFlightClient client() { return c.client(); }
            @Override public String url() { return c.url(); }
            @Override public String lastMessage() { return c.lastMessage(); }
            @Override public void close() { c.close(); }
        };
    }

    @Override
    public TensorBrowserPanel.Connection connect(String url, String token) throws IOException {
        Dial dial = dials.get();
        boolean ok = false;
        try {
            if (!dial.connect(url, token, url == null ? biopb.tensor.Connection.START_TIMEOUT : TYPED_TIMEOUT)) {
                String why = dial.lastMessage();
                throw new IOException(why == null || why.isEmpty() ? "Could not connect" : why);
            }
            TensorFlightClient flight = dial.client();
            FlightCatalogClient catalog = new FlightCatalogClient(flight);
            String dialed = dial.url();
            Dial owner = dial;
            ok = true; // the returned connection owns the dial now
            return new TensorBrowserPanel.Connection() {
                @Override public CatalogClient catalog() { return catalog; }
                @Override public TensorFlightClient flight() { return flight; }
                @Override public String url() { return dialed; }
                @Override public void close() { owner.close(); }
            };
        } finally {
            if (!ok) {
                dial.close();
            }
        }
    }
}
