package io.github.jiyuuchc.biopb.fiji;

import java.awt.Color;

/** What the one-line connection status says: a state (the dot's colour), a short text and a detail tooltip. */
final class ConnectionStatus {
    /** The connection state the dot shows. */
    enum State {
        NOT_CONNECTED(new Color(0x9E9E9E)),
        CONNECTING(new Color(0xF2A900)),
        CONNECTED(new Color(0x2E9E4F)),
        ERROR(new Color(0xD93A3A));

        private final Color color;

        State(Color color) { this.color = color; }

        Color color() { return color; }
    }

    private ConnectionStatus() { }

    /**
     * The short text of a connection: {@code Connected - local}, {@code Connected - remote (TLS)}, or plain
     * {@code Connected} when the URL is unknown.
     *
     * @param url the URL dialed, or {@code null}
     */
    static String connectedText(String url) {
        if (url == null) {
            return "Connected";
        }
        if (TensorBrowserPanel.isLoopback(url)) {
            return "Connected - local";
        }
        return "Connected - remote (" + (TensorBrowserPanel.isTls(url) ? "TLS" : "plaintext") + ")";
    }

    /** The tooltip of a connection: {@code Connected to host:port (TLS|plaintext)}. */
    static String connectedDetail(String described) { return "Connected to " + described; }
}
