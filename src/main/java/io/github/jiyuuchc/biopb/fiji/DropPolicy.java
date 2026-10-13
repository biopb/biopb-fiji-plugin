package io.github.jiyuuchc.biopb.fiji;

/**
 * Whether the browser can take a dropped (or picked) local path right now, and the text that says why
 * not. The same gate as the napari widget's: a path is a client-side filesystem path, meaningful to the
 * server only when they share a disk, so adding is enabled only against a connected, local server.
 */
public final class DropPolicy {
    private DropPolicy() { }

    /** Shown while a connect is running or nothing is connected. */
    public static final String NOT_CONNECTED = "Not connected — connect to add data by drag-drop";
    /** Shown against a server on another machine. */
    public static final String REMOTE = "Connected to a remote server — drag-drop unavailable";
    /** Shown while an add is running. */
    public static final String ADDING = "Adding data…";
    /** The ambient hint when a drop would be accepted. */
    public static final String READY = "Drop image files or folders here to add them";

    /** The answer: whether adding is possible, and the text to display either way. */
    public static final class Decision {
        private final boolean ok;
        private final String reason;

        Decision(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }

        public boolean isOk() { return ok; }
        /** The hint line: what to do when ok, why not otherwise. */
        public String getReason() { return reason; }
    }

    /**
     * @param connecting a connect is in progress
     * @param connected a connection is installed
     * @param url the URL dialed, or {@code null} if unknown (counts as remote, as an empty URL does in napari)
     * @param adding an add is running (one at a time)
     */
    public static Decision decide(boolean connecting, boolean connected, String url, boolean adding) {
        if (connecting || !connected) {
            return new Decision(false, NOT_CONNECTED);
        }
        if (url == null || url.isEmpty() || !isLocalUrl(url)) {
            return new Decision(false, REMOTE);
        }
        if (adding) {
            return new Decision(false, ADDING);
        }
        return new Decision(true, READY);
    }

    /**
     * Whether {@code url} points at this machine, as napari's {@code is_local_url}: a URL with no host
     * (a bare scheme, an empty authority) counts as local; otherwise the host must be {@code localhost}
     * or a loopback address (this plugin's {@link TensorBrowserPanel#isLoopback}, which also takes the
     * rest of {@code 127.0.0.0/8} and the IPv4-mapped loopback). Names are never resolved.
     */
    public static boolean isLocalUrl(String url) {
        if (url == null) {
            return false;
        }
        String authority = url.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "").replaceFirst("[/?#].*$", "");
        if (authority.isEmpty() || authority.startsWith(":")) {
            return true;
        }
        return TensorBrowserPanel.isLoopback(url);
    }
}
