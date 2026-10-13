package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.TensorFlightClient;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * Browses the catalog of a tensor server as a tree and asks its
 * {@link TensorBrowserListener} to open what the user picks.
 *
 * <p>The tree is read a folder at a time ({@link SourceList}): opening a folder
 * reads it on a worker, and the rows are grafted in when they land, without
 * rebuilding the rest. A full rebuild (the watcher re-listing, a search cleared)
 * keeps what is open, the selection and the scroll. Search is server-side only.
 *
 * <p>One resolve runs at a time. Replacing the connection or disposing the panel asks it to stop and
 * forgets it (its worker is detached, not joined, and its outcome is dropped).
 *
 * <p>Threading: all state lives on the event dispatch thread. Every server call
 * (connect, read a folder, search) runs on a {@link SwingWorker}; the
 * {@link SourceList} change callback, which arrives on its watcher thread, is
 * marshalled to the EDT. {@link SourceList} reads the open folders through a
 * volatile snapshot kept up to date on the EDT. {@link SourceList} keeps its sources and levels as volatile
 * immutable snapshots and takes its lock in {@code addSources}, so the search worker may call that off the EDT,
 * and the EDT's reads of the snapshots never wait on server I/O.
 */
public class TensorBrowserPanel extends JPanel {
    private static final Logger LOG = Logger.getLogger(TensorBrowserPanel.class.getName());
    /** The default server address when {@code BIOPB_TENSOR_URL} is unset. */
    public static final String DEFAULT_ADDRESS = "localhost:8815";
    private static final int DEFAULT_PORT = 8815;
    private static final int DEBOUNCE_MS = 300;
    private static final int OUTER_MARGIN = 10;
    private static final int ROW_GAP = 6;
    /** The least the panel lays out in; the window's own minimum adds its status bar and decorations. */
    static final Dimension MIN_SIZE = new Dimension(380, 380);

    private static GridBagConstraints cell(int x, int y, int w, int h, double weightx, int fill, Insets insets) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = w;
        c.gridheight = h;
        c.weightx = weightx;
        c.fill = fill;
        c.insets = insets;
        return c;
    }

    /** A live connection to a server. */
    public interface Connection extends AutoCloseable {
        /** The catalog reads go through. */
        CatalogClient catalog();

        /** The Flight client, for reading tensors; {@code null} for a test double. */
        TensorFlightClient flight();

        /** The URL dialed (scheme included), or {@code null} if unknown (a test double). */
        default String url() { return null; }

        @Override
        void close();
    }

    /** Opens connections; the seam tests replace. */
    public interface ClientFactory {
        /**
         * Connects to a server (called off the EDT).
         *
         * @param url {@code grpc://}, {@code grpc+tcp://} or {@code grpc+tls://} URL, or {@code null}
         *     to discover the server
         * @param token the bearer token for {@code url}, or {@code null}; never log it
         * @throws IOException with a message fit to show the user
         */
        Connection connect(String url, String token) throws IOException;
    }

    /** The real factory: the SDK's {@code Connection} (TLS trust, shared pin file, discovery). */
    public static ClientFactory flightFactory() {
        return new ServerConnector();
    }

    /** The token to start with: {@code BIOPB_TENSOR_TOKEN} if {@code env} has it, else empty. */
    public static String defaultToken(Function<String, String> env) {
        String v = env.apply("BIOPB_TENSOR_TOKEN");
        return v == null ? "" : v.trim();
    }

    /**
     * The URL the SDK dials for a typed address: {@code host[:port]} is plaintext
     * ({@code grpc://}); {@code grpc://}, {@code grpc+tcp://}, {@code grpc+tls://} and
     * {@code grpcs://} keep their scheme. The path is dropped; the default port is filled in.
     */
    static String toUrl(String address) {
        String a = address.trim();
        java.util.regex.Matcher m = SCHEME.matcher(a);
        String scheme = "grpc";
        if (m.find()) { // the scheme is read and checked once, before the host and port are parsed
            scheme = m.group(1).toLowerCase(java.util.Locale.ROOT);
            if (!scheme.matches("grpc|grpc\\+tcp|grpc\\+tls|grpcs")) {
                throw new IllegalArgumentException("Unsupported scheme '" + scheme + "://' (use grpc://, grpc+tls:// or host:port)");
            }
            a = a.substring(m.end());
        }
        Object[] hp = parseAddress(a);
        String host = (String) hp[0];
        return scheme + "://" + (host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + hp[1];
    }

    private static final java.util.regex.Pattern SCHEME = java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://");

    /**
     * Whether {@code url}'s host is this machine: {@code localhost} (also {@code localhost.}) or an IP
     * literal that is a loopback address ({@code 127.0.0.1}, {@code [::1]}, {@code ::ffff:127.0.0.1}).
     * Names are never resolved: {@code ip6-localhost} counts as remote.
     */
    static boolean isLoopback(String url) {
        String h = url.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "").replaceFirst("[/?#].*$", "");
        if (h.startsWith("[")) {
            h = h.substring(1, Math.max(1, h.indexOf(']') < 0 ? h.length() : h.indexOf(']')));
        } else if (h.indexOf(':') == h.lastIndexOf(':')) { // host or host:port, not a bare IPv6 literal
            h = h.replaceFirst(":\\d*$", "");
        }
        if (h.equalsIgnoreCase("localhost") || h.equalsIgnoreCase("localhost.")) {
            return true;
        }
        boolean v4 = h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (v4) {
            for (String octet : h.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return false; // not an address; Java would try DNS on it
                }
            }
        } else if (h.indexOf(':') < 0 || !h.matches("[0-9A-Fa-f:.%\\w]+")) {
            return false; // a name
        }
        try {
            return java.net.InetAddress.getByName(h).isLoopbackAddress(); // a literal: no lookup
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /** Whether {@code url} (as {@link #toUrl} returns it, or the SDK reports it) is a TLS address. */
    static boolean isTls(String url) {
        return url.regionMatches(true, 0, "grpc+tls://", 0, 11) || url.regionMatches(true, 0, "grpcs://", 0, 8);
    }

    /** {@code host:port (TLS)} or {@code host:port (plaintext)} for a dialed URL. */
    static String describe(String url) {
        return url.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "").replaceFirst("/.*$", "")
                + (isTls(url) ? " (TLS)" : " (plaintext)");
    }

    /** The address to start with: {@code BIOPB_TENSOR_URL} if {@code env} has it, else {@link #DEFAULT_ADDRESS}. */
    public static String defaultAddress(Function<String, String> env) {
        String v = env.apply("BIOPB_TENSOR_URL");
        return v == null || v.trim().isEmpty() ? DEFAULT_ADDRESS : v.trim();
    }

    /**
     * {@code [host, port]} of {@code host[:port]}: a leading scheme (such as {@code grpc://}) and
     * any path are dropped, and a bracketed IPv6 host ({@code [::1]:8815}) keeps its brackets.
     */
    static Object[] parseAddress(String address) {
        String a = address.trim().replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
        int end = a.length();
        for (char c : new char[] {'/', '?', '#'}) { // a path, query or fragment is dropped
            int i = a.indexOf(c);
            end = i >= 0 ? Math.min(end, i) : end;
        }
        a = a.substring(0, end);
        if (a.indexOf('@') >= 0) {
            throw new IllegalArgumentException("Bad address '" + address + "': no user name or password in the address");
        }
        if (a.isEmpty()) {
            throw new IllegalArgumentException("Enter host:port");
        }
        String host = a;
        String port = null;
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            if (close < 0 || close == 1 || close + 1 < a.length() && a.charAt(close + 1) != ':') {
                throw new IllegalArgumentException("Bad address '" + address + "'");
            }
            host = a.substring(0, close + 1);
            port = close + 1 < a.length() ? a.substring(close + 2) : null;
        } else if (a.indexOf(':') >= 0 && a.indexOf(':') == a.lastIndexOf(':')) {
            host = a.substring(0, a.indexOf(':'));
            port = a.substring(a.indexOf(':') + 1);
        } // else a bare name, or a bare IPv6 address: the default port
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Enter host:port");
        }
        if (port == null) {
            return new Object[] {host, DEFAULT_PORT};
        }
        try {
            int n = Integer.parseInt(port);
            if (n < 1 || n > 65535) {
                throw new NumberFormatException();
            }
            return new Object[] {host, n};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad port in '" + address + "'");
        }
    }

    private final ClientFactory factory;
    private final List<TensorBrowserListener> listeners = new CopyOnWriteArrayList<>();
    private final JTextField address = new JTextField(12);
    private final JPasswordField token = new JPasswordField(10);
    private final JButton connectButton = new JButton("Connect");
    private final ConnectionLabel connectionLabel = new ConnectionLabel(); // the connection, persistent
    private final JLabel status = new JLabel(" "); // transient messages (progress, copied, errors); hidden when empty
    private final JTextField search = new JTextField();
    private final JLabel searchStatus = new JLabel(" ");
    private final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree tree = new JTree(model) {
        private static final long serialVersionUID = 1L;

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            String msg = emptyMessage();
            if (msg != null) { // an empty tree says why, centred in what is visible
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                try {
                    Object hints = java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
                    if (hints instanceof Map) {
                        g2.addRenderingHints((Map<?, ?>) hints);
                    } else {
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    }
                    g2.setFont(getFont());
                    Color c = UIManager.getColor("Label.disabledForeground");
                    g2.setColor(c != null ? c : Color.GRAY);
                    java.awt.FontMetrics fm = g2.getFontMetrics();
                    java.awt.Rectangle vis = getVisibleRect();
                    g2.drawString(msg, vis.x + Math.max(4, (vis.width - fm.stringWidth(msg)) / 2),
                            vis.y + 24 + fm.getAscent());
                } finally {
                    g2.dispose();
                }
            }
        }
    };
    private final JScrollPane scroll = new JScrollPane(tree);
    private final Timer debounce;

    // EDT-only state.
    private Connection connection;
    private String connectionName = "";
    private String searchQuery = ""; // the query the search view shows; set when its answer lands
    private boolean inFlight; // a search is out ...
    private String inFlightQuery = ""; // ... for this query ...
    private boolean inFlightFresh; // ... and its landing should open the top folders
    private boolean disposed;
    private final Set<String> openSources = new HashSet<>(); // multi-tensor sources open
    private SourceList list;
    private int connectGen;
    private boolean connecting; // EDT: a connect is out
    private String connectedText = ""; // the current connection's line, kept to restore after a failed re-connect
    private String connectedDetail = "";
    private java.util.function.Consumer<String> errorDialog = this::showConnectError;
    private final Runnable syncToken = () -> {
        boolean discover = address.getText().trim().isEmpty();
        token.setEnabled(connectButton.isEnabled() && !discover);
        token.setToolTipText(discover
                ? "Not used when the address is empty: discovery finds the token itself (BIOPB_TENSOR_TOKEN or the control plane)"
                : "Bearer token, sent only to the address typed here (refused over plaintext to a non-local host)");
    };
    private boolean dialogOpen; // EDT: a connect-error dialog is up
    private int searchGen;
    private final Set<String> expanded = new HashSet<>(); // folders open in the folder view
    private Set<String> searchOpen = new HashSet<>(); // folders open in the search view
    private final Set<String> loading = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private Map<String, DefaultMutableTreeNode> folderNodes = new HashMap<>();
    private Map<String, Map<String, Object>> searchResult; // null: the folder view
    private boolean initialExpandDone;
    private boolean rebuilding;
    private String selectedId;
    private String viewKey = "";
    private volatile Set<String> openSnapshot = Collections.emptySet(); // read by SourceList's threads
    private String resolvingId; // the one running resolve (they are serialized, as napari's modal flow is) ...
    private AtomicBoolean resolveCancel; // ... and the flag that asks it to stop
    private String progressText = " "; // what the status line shows for it
    private final Timer noteTimer = new Timer(2500, e -> {
        if (resolvingId != null) {
            showMessage(progressText);
        }
    });
    private final JButton cancelResolve = new JButton("Cancel resolve");
    private final MetadataPane infoPane = new MetadataPane(v -> showMessage("Copied " + v), this::copyFailed);
    // A few daemon threads and a queue, so a stalled server cannot pile up threads. Cancelling a read interrupts
    // it, but that may not unblock a Flight call: a hung one can keep its thread until the call times out.
    private final java.util.concurrent.ThreadPoolExecutor metadataReads = newMetadataPool();
    private final Set<MetadataDialog> metadataDialogs = new HashSet<>(); // open, non-modal; closed with the connection
    private final SourceEditor editor = new SourceEditor(new SourceEditor.Host() {
        @Override public SourceList list() { return list; }
        @Override public String url() { return connection == null ? null : connection.url(); }
        @Override public boolean connecting() { return !disposed && !connectButton.isEnabled(); }
        @Override public Component parent() { return TensorBrowserPanel.this; }
        @Override public void status(String text) { showMessage(text); }
        @Override public void catalogChanged() { onCatalogChanged(); }
    });
    /** Asks (title, message) before a cloud download; true to go ahead. Tests replace it. */
    BiPredicate<String, String> confirmDownload = (title, message) -> JOptionPane.showConfirmDialog(this, message,
            title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;

    /** A panel connecting through {@link #flightFactory()}, starting at {@code BIOPB_TENSOR_URL}. */
    public TensorBrowserPanel() {
        this(flightFactory(), defaultAddress(System::getenv), defaultToken(System::getenv));
    }

    /**
     * @param factory opens connections
     * @param initialAddress the address field's initial text
     */
    public TensorBrowserPanel(ClientFactory factory, String initialAddress) {
        this(factory, initialAddress, "");
    }

    /**
     * @param factory opens connections
     * @param initialAddress the address field's initial text (empty: discover the server)
     * @param initialToken the token field's initial text
     */
    public TensorBrowserPanel(ClientFactory factory, String initialAddress, String initialToken) {
        super(new BorderLayout(0, ROW_GAP));
        this.factory = (u, t) -> new RefCountedConnection(factory.connect(u, t));
        address.setText(initialAddress);
        address.setToolTipText("host:port, grpc://host:port or grpc+tls://host:port; empty: discover the local server");
        token.setText(initialToken);
        syncToken.run();
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(0); // double-click opens; the handles expand
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);

        placeholder(address, "host:port - empty = auto-discover");
        placeholder(search, "Search sources...");
        token.setMinimumSize(new Dimension(60, token.getPreferredSize().height));
        address.setMinimumSize(new Dimension(80, address.getPreferredSize().height));
        cancelResolve.setVisible(false);
        JPanel statusRow = new JPanel(new BorderLayout(6, 0));
        statusRow.add(connectionLabel, BorderLayout.CENTER);
        statusRow.add(cancelResolve, BorderLayout.EAST);
        status.setMinimumSize(new Dimension(0, status.getPreferredSize().height)); // elided, never widens the window
        status.setVisible(false);
        search.setToolTipText("Search sources by id, URL or type (on the server)");
        editor.acceptDropsOn(this, tree, scroll, scroll.getViewport());

        // Top: a two-row form (the address stretches, Connect is never pushed out), the connection line, the
        // message line, the add row and the search field, one column with an even gap between rows.
        JPanel top = new JPanel(new GridBagLayout());
        top.add(new JLabel("Server"), cell(0, 0, 1, 1, 0, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 6)));
        top.add(address, cell(1, 0, 1, 1, 1, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 6)));
        top.add(connectButton, cell(2, 0, 1, 2, 0, GridBagConstraints.BOTH, new Insets(0, 0, 0, 0))); // beside both fields
        top.add(new JLabel("Token"), cell(0, 1, 1, 1, 0, GridBagConstraints.HORIZONTAL, new Insets(ROW_GAP, 0, 0, 6)));
        top.add(token, cell(1, 1, 1, 1, 1, GridBagConstraints.HORIZONTAL, new Insets(ROW_GAP, 0, 0, 6)));
        java.awt.Component[] rows = {statusRow, status, editor.view(), search};
        for (int i = 0; i < rows.length; i++) {
            top.add(rows[i], cell(0, 2 + i, 3, 1, 0, GridBagConstraints.HORIZONTAL, new Insets(ROW_GAP, 0, 0, 0)));
        }
        JPanel bottom = new JPanel(new BorderLayout(0, 2));
        searchStatus.setForeground(UIManager.getColor("Label.disabledForeground"));
        bottom.add(searchStatus, BorderLayout.NORTH);
        bottom.add(infoPane, BorderLayout.CENTER);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(OUTER_MARGIN, OUTER_MARGIN, 0, OUTER_MARGIN));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        setMinimumSize(MIN_SIZE);

        debounce = new Timer(DEBOUNCE_MS, e -> applyFilter());
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        connectButton.addActionListener(e -> connect());
        cancelResolve.addActionListener(e -> cancelResolve());
        address.addActionListener(e -> connect());
        address.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { syncToken.run(); }
            @Override public void removeUpdate(DocumentEvent e) { syncToken.run(); }
            @Override public void changedUpdate(DocumentEvent e) { syncToken.run(); }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override public void treeExpanded(TreeExpansionEvent e) { onExpanded(e.getPath()); }
            @Override public void treeCollapsed(TreeExpansionEvent e) { onCollapsed(e.getPath()); }
        });
        tree.addTreeSelectionListener(e -> {
            if (!rebuilding) {
                TreeModelBuilder.Node n = TreeModelBuilder.nodeOf(tree.getLastSelectedPathComponent());
                selectedId = n == null || n.getKind() == TreeModelBuilder.Kind.NOTE ? null : n.getId();
                updateInfoPane();
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    TreeModelBuilder.Node n = nodeAt(e.getPoint());
                    if (n != null && n.getKind() == TreeModelBuilder.Kind.SOURCE
                            && n.getUnresolved() != TreeModelBuilder.Unresolved.NONE) {
                        resolveSource(n.getKey()); // an unresolved source has nothing to open: resolve it
                    } else if (n != null && n.isOpenable()) {
                        fireOpen(n.getArrayIds().get(0), 0);
                    }
                }
            }
        });
    }

    // --- public API ----------------------------------------------------------

    /** Adds a listener for open and ROI requests. */
    public void addListener(TensorBrowserListener l) { listeners.add(l); }

    /** Removes a listener. */
    public void removeListener(TensorBrowserListener l) { listeners.remove(l); }

    /** The current connection, or {@code null} if not connected. Call on the EDT. */
    public Connection getConnection() { return connection; }

    /** The current connection as a shared one (images hold references to it), or {@code null}. EDT. */
    public RefCountedConnection getSharedConnection() {
        return connection instanceof RefCountedConnection ? (RefCountedConnection) connection : null;
    }

    /** The current Flight client, or {@code null} if not connected (or a test double). Call on the EDT. */
    public TensorFlightClient getClient() { return connection == null ? null : connection.flight(); }

    /** Connects to the address in the field (asynchronous); a no-op while the field is invalid. */
    public void connect() {
        if (disposed || !connectButton.isEnabled()) {
            return; // one connect at a time
        }
        boolean discover = address.getText().trim().isEmpty();
        String url;
        try {
            url = discover ? null : toUrl(address.getText());
        } catch (IllegalArgumentException e) {
            showMessage(e.getMessage());
            return;
        }
        char[] typed = token.getPassword();
        String secret = typed.length == 0 || discover ? null : new String(typed).trim();
        java.util.Arrays.fill(typed, '\0');
        String tokenArg = secret == null || secret.isEmpty() ? null : secret;
        if (tokenArg != null && !isTls(url) && !isLoopback(url)) {
            String msg = "Not sending the token to " + describe(url) + ": it would cross the network unencrypted. "
                    + "Use grpc+tls://" + url.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "") + " (or clear the Token field).";
            showMessage(msg);
            notifyError(msg, connectGen);
            return;
        }
        String target = discover ? "the discovered server" : describe(url);
        int gen = ++connectGen;
        connectButton.setEnabled(false);
        address.setEnabled(false);
        token.setEnabled(false);
        editor.updateHint();
        connecting = true;
        showMessage(" ");
        showState(ConnectionStatus.State.CONNECTING, "Connecting\u2026", "Connecting to " + target + "\u2026");
        new SwingWorker<Object[], Void>() {
            @Override protected Object[] doInBackground() throws Exception {
                Connection c = factory.connect(url, tokenArg);
                SourceList l = null;
                try {
                    l = new SourceList(c.catalog());
                    l.refresh();
                } catch (Exception | Error e) {
                    release(c, l); // the connection just opened must not leak
                    throw e;
                }
                return new Object[] {c, l};
            }

            @Override protected void done() {
                connecting = false;
                if (!disposed) { // however it ended, the controls are usable again
                    connectButton.setEnabled(true);
                    address.setEnabled(true);
                    syncToken.run();
                    editor.updateHint();
                }
                Object[] r;
                try {
                    r = get();
                } catch (Exception e) {
                    if (gen == connectGen) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        String why = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
                        if (connection == null) {
                            showState(ConnectionStatus.State.ERROR, "Connection failed", why);
                            showMessage("Connection failed: " + why);
                        } else { // the old connection stays: it is still the connection line
                            showState(ConnectionStatus.State.CONNECTED, connectedText, connectedDetail);
                            showMessage("Connection to " + target + " failed (" + why + "); still connected to "
                                    + connectionName);
                        }
                        notifyError(why, gen);
                    }
                    return;
                }
                Connection c = (Connection) r[0];
                SourceList l = (SourceList) r[1];
                if (gen != connectGen) { // superseded, or the panel was disposed
                    release(c, l);
                    return;
                }
                String dialed = c.url() != null ? c.url() : url;
                install(c, l, dialed != null ? describe(dialed) : "the discovered server", dialed);
            }
        }.execute();
    }

    /** Replaces the dialog shown when a connect fails (tests; the default is a message dialog). EDT. */
    void setErrorDialog(java.util.function.Consumer<String> dialog) { errorDialog = dialog; }

    /** Shows {@code message} in a dialog unless headless, disposed, stale ({@code gen}) or one is already up. */
    private void notifyError(String message, int gen) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return; // the status line has it
        }
        SwingUtilities.invokeLater(() -> {
            if (disposed || gen != connectGen || dialogOpen) {
                return;
            }
            dialogOpen = true;
            try {
                errorDialog.accept(message);
            } finally {
                dialogOpen = false;
            }
        });
    }

    private void showConnectError(String message) {
        javax.swing.JTextArea text = new javax.swing.JTextArea(message, Math.min(12, message.length() / 60 + 2), 60);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setOpaque(false);
        JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this), new JScrollPane(text),
                "Connection failed", JOptionPane.ERROR_MESSAGE);
    }

    /** Stops the watcher and closes the connection; the panel is not used again. */
    public void dispose() {
        disposed = true;
        closeMetadataDialogs();
        metadataReads.shutdownNow();
        forgetResolve();
        connectGen++;
        searchGen++;
        inFlight = false;
        debounce.stop();
        Thread closing = release(connection, list);
        connection = null;
        list = null;
        editor.connectionChanged();
        folderNodes = new HashMap<>();
        if (closing != null && !SwingUtilities.isEventDispatchThread()) {
            try { // a short wait, so a JVM exit right after rarely cuts the close off
                closing.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // --- connection ----------------------------------------------------------

    private static Thread release(Connection c, SourceList l) {
        if (l == null && c == null) {
            return null;
        }
        // Closing stops the watcher's executor and shuts a channel down, either of
        // which can wait; neither belongs on the EDT.
        Thread t = new Thread(() -> {
            try {
                if (l != null) {
                    l.close();
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Closing the source list failed", e);
            } finally {
                try {
                    if (c != null) {
                        c.close();
                    }
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Closing the connection failed", e);
                }
            }
        }, "biopb-close");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void install(Connection c, SourceList l, String name, String dialed) {
        forgetResolve();
        closeMetadataDialogs();
        release(connection, list);
        connection = c;
        connectionName = name;
        list = l;
        editor.connectionChanged();
        searchGen++;
        searchResult = null;
        searchQuery = "";
        inFlight = false;
        viewKey = "";
        folderNodes = new HashMap<>();
        openSources.clear();
        expanded.clear();
        searchOpen = new HashSet<>();
        loading.clear();
        failed.clear();
        initialExpandDone = false;
        selectedId = null;
        search.setText("");
        debounce.stop();
        searchStatus.setText(" ");
        publishOpen();
        l.setOpenKeys(() -> openSnapshot);
        l.setOnChanged(sources -> SwingUtilities.invokeLater(() -> {
            if (list == l) { // the catalog changed: redraw, or re-run the active search in place
                onCatalogChanged();
            }
        }));
        l.startWatch();
        connectedText = ConnectionStatus.connectedText(dialed)
                + (l.isLazy() ? "" : " \u2022 " + l.getSources().size() + " sources");
        connectedDetail = ConnectionStatus.connectedDetail(name);
        showMessage(" ");
        showState(ConnectionStatus.State.CONNECTED, connectedText, connectedDetail);
        rebuild();
    }

    private void publishOpen() {
        openSnapshot = Collections.unmodifiableSet(new HashSet<>(expanded));
    }

    // --- building ------------------------------------------------------------

    private Set<String> openSet() { return searchResult != null ? searchOpen : expanded; }

    /** Redraws the whole tree from the current state, keeping what is open, the selection and the scroll. */
    private void rebuild() {
        if (list == null) {
            model.setRoot(new DefaultMutableTreeNode());
            folderNodes = new HashMap<>();
            return;
        }
        String key = searchResult != null ? "search:" + searchQuery : "tree"; // the view shown
        boolean keepView = key.equals(viewKey);
        viewKey = key;
        Point pos = scroll.getViewport().getViewPosition();
        DefaultMutableTreeNode root;
        if (searchResult != null) {
            root = TreeModelBuilder.buildPaths(searchResult.values());
        } else if (list.isLazy()) {
            Map<String, CatalogClient.Level> levels = list.getLevels();
            CatalogClient.Level top = levels.get("");
            if (top != null && !initialExpandDone) { // a lone top folder is the way in: open it
                if (top.getFolders().size() == 1 && top.getSources().isEmpty()) {
                    expanded.add(top.getFolders().get(0).getKey());
                    publishOpen();
                }
                initialExpandDone = true;
            }
            root = TreeModelBuilder.buildLevels(levels, list.getSources(), expanded, failed);
        } else {
            root = TreeModelBuilder.buildPaths(list.getSources().values());
            if (!initialExpandDone && root.getChildCount() > 0) {
                expanded.addAll(TreeModelBuilder.topFolders(root, 1));
                initialExpandDone = true;
            }
        }
        rebuilding = true;
        try {
            model.setRoot(root);
            folderNodes = TreeModelBuilder.folderIndex(root);
            restoreExpansion(root);
            restoreSelection(!keepView);
        } finally {
            rebuilding = false;
        }
        updateInfoPane();
        if (keepView) {
            SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(pos));
        }
    }

    private void restoreExpansion(DefaultMutableTreeNode under) {
        TreeModelBuilder.Node n = TreeModelBuilder.nodeOf(under);
        if (n != null && (n.getKind() == TreeModelBuilder.Kind.FOLDER && openSet().contains(n.getKey())
                || n.getKind() == TreeModelBuilder.Kind.SOURCE && under.getChildCount() > 0
                        && openSources.contains(n.getKey()))) {
            tree.expandPath(new TreePath(under.getPath()));
        }
        for (int i = 0; i < under.getChildCount(); i++) {
            restoreExpansion((DefaultMutableTreeNode) under.getChildAt(i));
        }
    }

    private void restoreSelection(boolean scrollTo) {
        if (selectedId == null) {
            return;
        }
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        for (java.util.Enumeration<?> e = root.preorderEnumeration(); e.hasMoreElements();) {
            DefaultMutableTreeNode t = (DefaultMutableTreeNode) e.nextElement();
            TreeModelBuilder.Node n = TreeModelBuilder.nodeOf(t);
            if (n != null && selectedId.equals(n.getId())) {
                TreePath p = new TreePath(t.getPath());
                tree.setSelectionPath(p);
                if (scrollTo) {
                    tree.scrollPathToVisible(p);
                }
                return;
            }
        }
    }

    // --- folders -------------------------------------------------------------

    private void onExpanded(TreePath path) {
        TreeModelBuilder.Node n = TreeModelBuilder.nodeOf(path.getLastPathComponent());
        if (n != null && n.getKind() == TreeModelBuilder.Kind.SOURCE) {
            openSources.add(n.getKey());
        }
        if (n == null || n.getKind() != TreeModelBuilder.Kind.FOLDER) {
            return;
        }
        openSet().add(n.getKey());
        if (searchResult == null) {
            publishOpen();
            // A failed folder is tried again when opened again, not by the rebuild that shows the failure.
            if (list != null && list.isLazy() && !list.getLevels().containsKey(n.getKey())
                    && !failed.contains(n.getKey())) {
                loadLevel(n.getKey());
            }
        }
    }

    private void onCollapsed(TreePath path) {
        TreeModelBuilder.Node n = TreeModelBuilder.nodeOf(path.getLastPathComponent());
        if (rebuilding || n == null) {
            return;
        }
        if (n.getKind() == TreeModelBuilder.Kind.SOURCE) {
            openSources.remove(n.getKey());
        }
        if (n.getKind() != TreeModelBuilder.Kind.FOLDER) {
            return;
        }
        openSet().remove(n.getKey());
        if (searchResult == null) {
            failed.remove(n.getKey());
            publishOpen();
        }
    }

    private void loadLevel(String key) {
        SourceList l = list;
        if (l == null || !loading.add(key)) {
            return;
        }
        failed.remove(key);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                l.loadLevel(key);
                return null;
            }

            @Override protected void done() {
                if (l != list) {
                    return; // another connection now
                }
                loading.remove(key);
                try {
                    get();
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Reading folder '" + key + "' failed", e);
                    failed.add(key);
                }
                graft(key);
            }
        }.execute();
    }

    /** Fills one folder from its level (or its failure) and leaves the rest of the tree alone. */
    private void graft(String key) {
        DefaultMutableTreeNode folder = searchResult == null && list != null ? folderNodes.get(key) : null;
        if (folder == null) {
            return; // not on screen; the next full build shows it
        }
        folder.removeAllChildren();
        for (DefaultMutableTreeNode c : TreeModelBuilder.levelChildren(key, list.getLevels(), list.getSources(),
                expanded, failed)) {
            folder.add(c);
        }
        rebuilding = true;
        try {
            model.nodeStructureChanged(folder);
            folderNodes = TreeModelBuilder.folderIndex((DefaultMutableTreeNode) model.getRoot());
            restoreExpansion(folder);
            restoreSelection(false);
        } finally {
            rebuilding = false;
        }
    }

    // --- search --------------------------------------------------------------

    /** Applies the search box: runs the search, or restores the folder tree when it is empty. */
    private void applyFilter() {
        String q = search.getText().trim().toLowerCase();
        if (list == null) {
            return;
        }
        if (q.isEmpty()) {
            if (searchResult != null || inFlight) { // back to the folder tree
                searchGen++; // an answer still in flight is no longer wanted
                inFlight = false;
                searchQuery = "";
                searchResult = null;
                searchStatus.setText(" ");
                rebuild();
            }
        } else if (!(inFlight ? q.equals(inFlightQuery) : q.equals(searchQuery) && searchResult != null)) {
            runSearch(q, true); // a search already out for this query is left to land
        }
    }

    /** The catalog changed under us: redraw, or re-run the active search in place. */
    private void onCatalogChanged() {
        if (inFlight) {
            runSearch(inFlightQuery, false); // carries the fresh step of the one it supersedes
        } else if (searchQuery.isEmpty() || searchResult == null) {
            rebuild();
        } else {
            runSearch(searchQuery, false);
        }
    }

    /**
     * Asks the server for the matches of {@code q}, superseding any search in flight.
     *
     * @param fresh a new query: its top folders open. A re-run for a changed catalog
     *        keeps what the user has open.
     */
    private void runSearch(String q, boolean fresh0) {
        boolean fresh = fresh0 || inFlight && inFlightFresh;
        inFlight = true;
        inFlightQuery = q;
        inFlightFresh = fresh;
        int gen = ++searchGen;
        SourceList l = list;
        Connection c = connection;
        searchStatus.setText("Server-side search \u2022 Searching\u2026");
        new SwingWorker<Object[], Void>() {
            @Override protected Object[] doInBackground() throws Exception {
                CatalogClient client = c.catalog();
                List<Map<String, Object>> rows = client.query(
                        TreeModelBuilder.searchSql(q, SourceList.sourceColumns(client)));
                boolean more = rows.size() > TreeModelBuilder.SEARCH_LIMIT;
                Map<String, Map<String, Object>> found = new LinkedHashMap<>();
                for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), TreeModelBuilder.SEARCH_LIMIT))) {
                    if (row.get("source_id") instanceof String) {
                        found.put((String) row.get("source_id"), row);
                    }
                }
                l.addSources(found); // keep the descriptors, as the lazy listing does not hold them
                return new Object[] {found, more};
            }

            @Override @SuppressWarnings("unchecked") protected void done() {
                if (gen != searchGen) {
                    return; // superseded while in flight
                }
                inFlight = false;
                try {
                    Object[] r = get();
                    searchResult = (Map<String, Map<String, Object>>) r[0];
                    searchStatus.setText("Server-side search \u2022 " + searchResult.size() + " match"
                            + (searchResult.size() == 1 ? "" : "es")
                            + ((Boolean) r[1] ? " \u2022 first " + TreeModelBuilder.SEARCH_LIMIT
                                    + " shown, refine the search" : ""));
                    searchQuery = q;
                    if (fresh) {
                        searchOpen = new HashSet<>(TreeModelBuilder.topFolders(
                                TreeModelBuilder.buildPaths(searchResult.values()), 1));
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Search failed", e);
                    if (fresh || searchResult == null) {
                        // Not the previous query's matches (or folders) under this query's name.
                        searchResult = new LinkedHashMap<>();
                        searchOpen = new HashSet<>();
                        searchQuery = "";
                        searchStatus.setText("Search failed \u2022 the server did not answer");
                    } else { // a re-run for a changed catalog: the last answer still stands
                        searchStatus.setText("Search failed \u2022 showing the previous results");
                    }
                }
                rebuild();
            }
        }.execute();
    }

    // --- actions -------------------------------------------------------------

    private TreeModelBuilder.Node nodeAt(Point p) {
        TreePath path = tree.getPathForLocation(p.x, p.y);
        return path == null ? null : TreeModelBuilder.nodeOf(path.getLastPathComponent());
    }

    private void fireOpen(String arrayId, int level) {
        for (TensorBrowserListener l : listeners) {
            l.openRequested(arrayId, level);
        }
    }

    private void fireRois(String arrayId) {
        String refusal = TensorBrowserController.roisRefusal(arrayId);
        if (refusal != null) {
            say(refusal);
            return;
        }
        for (TensorBrowserListener l : listeners) {
            l.roisRequested(arrayId);
        }
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        TreeModelBuilder.Node n = path == null ? null : TreeModelBuilder.nodeOf(path.getLastPathComponent());
        if (n == null) {
            return;
        }
        tree.setSelectionPath(path);
        JPopupMenu menu = menuFor(n);
        String removeRoot = removeRootOf(path);
        if (removeRoot != null && editor.canChange()) { // omitted while an add or removal runs, or against a remote server
            if (menu == null) {
                menu = new JPopupMenu();
            } else {
                menu.addSeparator();
            }
            menu.add(item("Remove source\u2026", () -> editor.remove(removeRoot, n.getText())));
        }
        if (menu != null) {
            menu.show(tree, e.getX(), e.getY());
        }
    }

    /**
     * The {@code dnd://} root a top-level row can be removed by, or {@code null}: only what was added
     * by drop or with the Add button has one, and only at the top of the tree.
     */
    String removeRootOf(TreePath path) {
        SourceList l = list;
        if (l == null || path.getPathCount() != 2) { // the root, then a top-level row
            return null;
        }
        TreeModelBuilder.Node n = TreeModelBuilder.nodeOf(path.getLastPathComponent());
        if (n == null) {
            return null;
        }
        if (n.getKind() == TreeModelBuilder.Kind.FOLDER) {
            return DroppedSources.removeRootForFolder(n.getKey(), l.getDroppedRoots());
        }
        if (n.getKind() == TreeModelBuilder.Kind.SOURCE) {
            Map<String, Object> d = l.getSources().get(n.getKey());
            Object url = d == null ? null : d.get("source_url");
            return url instanceof String ? DroppedSources.removeRootForSource((String) url) : null;
        }
        return null;
    }

    /** The context menu of a row, or {@code null} for a row that opens nothing. */
    JPopupMenu menuFor(TreeModelBuilder.Node n) {
        List<String> ids = n.getArrayIds();
        if (n.getKind() == TreeModelBuilder.Kind.SOURCE && n.getUnresolved() != TreeModelBuilder.Unresolved.NONE) {
            JPopupMenu menu = new JPopupMenu(); // nothing else is offered until it is resolved
            String id = n.getKey();
            menu.add(id.equals(resolvingId) ? item("Cancel resolve", this::cancelResolve)
                    : item(n.getUnresolved() == TreeModelBuilder.Unresolved.CLOUD ? "Resolve (downloads file)…"
                            : n.getUnresolved() == TreeModelBuilder.Unresolved.FAILED ? "Retry loading" : "Load now",
                            () -> resolveSource(id)));
            menu.add(item("Metadata…", () -> showMetadata(n)));
            return menu;
        }
        if (n.getKind() == TreeModelBuilder.Kind.FOLDER || n.getKind() == TreeModelBuilder.Kind.NOTE
                || ids.isEmpty()) {
            return null;
        }
        JPopupMenu menu = new JPopupMenu();
        if (ids.size() == 1) {
            String id = ids.get(0);
            menu.add(item("Open", () -> fireOpen(id, 0)));
            menu.add(item("Open at coarser level…", () -> {
                JSpinner spinner = new JSpinner(new SpinnerNumberModel(1, 1, 16, 1));
                if (JOptionPane.showConfirmDialog(this, spinner, "Pyramid level", JOptionPane.OK_CANCEL_OPTION)
                        == JOptionPane.OK_OPTION) {
                    fireOpen(id, (Integer) spinner.getValue());
                }
            }));
            if (TensorBrowserController.roisRefusal(id) == null) {
                menu.add(item("Load ROIs", () -> fireRois(id)));
            }
        } else {
            menu.add(item("Open all " + ids.size() + " tensors", () -> {
                for (String id : new ArrayList<>(ids)) {
                    fireOpen(id, 0);
                }
            }));
        }
        menu.add(item("Metadata…", () -> showMetadata(n)));
        return menu;
    }

    // --- metadata ------------------------------------------------------------

    /**
     * What the pane and dialog say about row {@code n}, or {@code null} if it is not a source or tensor
     * the list has a row for. The source is the row's own (a tensor row knows the source it is under), looked
     * up by the id selection and resolve use; nothing is scanned.
     */
    private MetadataTarget targetOf(TreeModelBuilder.Node n) {
        SourceList l = list;
        if (l == null || n == null || n.getSourceId().isEmpty()
                || n.getKind() == TreeModelBuilder.Kind.FOLDER || n.getKind() == TreeModelBuilder.Kind.NOTE) {
            return null;
        }
        Map<String, Object> row = l.getSources().get(n.getSourceId());
        if (row == null) {
            return null;
        }
        String arrayId = n.getKind() == TreeModelBuilder.Kind.TENSOR || n.getKind() == TreeModelBuilder.Kind.LABEL
                ? n.getKey()
                : n.getArrayIds().size() == 1 ? n.getArrayIds().get(0) : null;
        return MetadataTarget.of(row, arrayId);
    }

    private void updateInfoPane() {
        infoPane.show(targetOf(TreeModelBuilder.nodeOf(tree.getLastSelectedPathComponent())));
    }

    private static java.util.concurrent.ThreadPoolExecutor newMetadataPool() {
        java.util.concurrent.ThreadPoolExecutor p = new java.util.concurrent.ThreadPoolExecutor(4, 4, 30,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "biopb-metadata");
                    t.setDaemon(true);
                    return t;
                });
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    private void copyFailed() { showMessage("Could not copy to the clipboard"); }

    /**
     * Opens the metadata window for row {@code n} at once, in a loading state, and reads the metadata
     * on a thread of the panel's own (so a hung call cannot hold up anything else). Asking again for what
     * is already open brings that window forward. Closing the window cancels its read; the answer is
     * dropped (and the window closed) if the connection was replaced or the panel disposed meanwhile; a
     * failure is shown in the window. A row with nothing to ask about says so in the status line.
     */
    void showMetadata(TreeModelBuilder.Node n) {
        Connection c = connection;
        if (c == null || disposed) {
            return;
        }
        MetadataTarget target = targetOf(n);
        if (target == null) {
            showMessage("No metadata for '" + n.getText() + "': the catalog has no usable entry for it");
            return;
        }
        for (MetadataDialog open : metadataDialogs) {
            if (open.key().equals(MetadataDialog.keyOf(target))) {
                open.toFront();
                open.requestFocus();
                return;
            }
        }
        MetadataDialog dialog = new MetadataDialog(SwingUtilities.getWindowAncestor(this), target);
        dialog.setOnCopied(v -> showMessage("Copied " + v));
        dialog.setOnCopyFailed(this::copyFailed);
        metadataDialogs.add(dialog);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                metadataDialogs.remove(dialog);
                dialog.cancelRead();
            }
        });
        dialog.setVisible(true);
        String sourceId = target.getSourceId();
        try {
            dialog.setRead(metadataReads.submit(() -> {
                String text = null;
                Throwable failure = null;
                try {
                    text = MetadataFormat.toText(c.catalog().getSourceMetadata(sourceId)); // formatted off the EDT
                } catch (Exception t) { // an Error is not ours to hide
                    failure = t;
                } catch (StackOverflowError | OutOfMemoryError e) { // formatting a huge document: say so, briefly
                    LOG.log(Level.WARNING, "Formatting the metadata of " + sourceId + " failed", e);
                    failure = new IllegalStateException("the metadata is too large to show (" + e.getClass().getSimpleName() + ")");
                }
                String shown = text;
                Throwable why = failure;
                if (Thread.currentThread().isInterrupted()) {
                    return; // cancelled: the window is gone
                }
                SwingUtilities.invokeLater(() -> {
                    if (disposed || c != connection || !dialog.isDisplayable()) {
                        dialog.dispose(); // stale (or closed by the user): nothing to show it in
                    } else if (why == null) {
                        dialog.showText(shown);
                    } else {
                        dialog.showError(metadataFailure(sourceId, why));
                    }
                });
            }));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            dialog.dispose(); // the panel is being disposed
        }
    }

    /** The message for a failed metadata read; an unsupporting server is an expected state, not logged as a fault. */
    private static String metadataFailure(String sourceId, Throwable why) {
        if (why instanceof UnsupportedOperationException) {
            LOG.log(Level.FINE, "Server has no source metadata for " + sourceId, why);
            return "this server does not provide source metadata";
        }
        LOG.log(Level.WARNING, "Could not read the metadata of " + sourceId, why);
        return OpenFailure.describe(why);
    }

    private void closeMetadataDialogs() {
        for (MetadataDialog d : new ArrayList<>(metadataDialogs)) {
            d.dispose(); // its windowClosed cancels the read
        }
        metadataDialogs.clear();
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> action.run());
        return i;
    }

    // --- resolving -----------------------------------------------------------

    /**
     * Resolves an unresolved source off the EDT, as the napari widget does: a cloud source asks first
     * (it downloads the file), a pending or failed one does not. One resolve runs at a time; a request
     * made meanwhile is ignored with a status note. Heartbeats and the outcome go to the status line and
     * the listeners, a failure also to {@link TensorBrowserListener#resolveFailed}. On success only that
     * source's row is replaced in place. An outcome that arrives after the connection was replaced, or
     * the panel disposed, is dropped.
     */
    void resolveSource(String sourceId) {
        SourceList l = list;
        if (l == null || disposed || TreeModelBuilder.unresolvedState(currentRow(l, sourceId)) == TreeModelBuilder.Unresolved.NONE) {
            return;
        }
        String name = nameOf(l, sourceId);
        if (resolvingId != null) {
            // A transient note over the progress line: not sent to the listeners, and put back after a moment.
            showMessage("Already resolving '" + (resolvingId.equals(sourceId) ? name : nameOf(l, resolvingId)) + "'");
            noteTimer.setRepeats(false);
            noteTimer.restart();
            return;
        }
        if (TreeModelBuilder.unresolvedState(currentRow(l, sourceId)) == TreeModelBuilder.Unresolved.CLOUD
                && !confirmDownload.test("Resolve cloud source", "Resolving '" + name
                + "' downloads the entire file from remote storage.\n\nThis may take several minutes, use local "
                + "disk space, and will not work offline. Continue?")) {
            return;
        }
        // The dialog ran a nested event loop: the world may have moved on.
        TreeModelBuilder.Unresolved state = TreeModelBuilder.unresolvedState(currentRow(l, sourceId));
        if (disposed || l != list || state == TreeModelBuilder.Unresolved.NONE || resolvingId != null) {
            if (!disposed && l == list) {
                say("'" + name + "' changed while asking; not resolved");
            }
            return;
        }
        String verb = state == TreeModelBuilder.Unresolved.CLOUD ? "Resolving" : "Loading";
        AtomicBoolean cancel = new AtomicBoolean();
        resolvingId = sourceId;
        resolveCancel = cancel;
        cancelResolve.setVisible(true);
        say(verb + " '" + name + "'…");
        new SwingWorker<Map<String, Object>, CatalogClient.ResolveProgress>() {
            @Override protected Map<String, Object> doInBackground() throws Exception {
                return l.resolve(sourceId, this::publish, cancel::get);
            }

            @Override protected void process(List<CatalogClient.ResolveProgress> chunks) {
                if (disposed || l != list || resolveCancel != cancel || cancel.get()) {
                    return;
                }
                CatalogClient.ResolveProgress p = chunks.get(chunks.size() - 1);
                String label = verb + " '" + (p.getTargetName().isEmpty() ? name : p.getTargetName()) + "'… "
                        + (long) p.getElapsedSeconds() + "s";
                say(p.getTargetBytes() > 0 ? label + " (" + humanBytes(p.getTargetBytes()) + ")" : label);
            }

            @Override protected void done() {
                if (disposed || l != list || resolveCancel != cancel) {
                    return; // another connection now (or disposed): the answer is dropped
                }
                resolvingId = null;
                resolveCancel = null;
                cancelResolve.setVisible(false);
                try {
                    Map<String, Object> row = get();
                    graftSource(sourceId, row); // the committed row, which a re-list cannot have reverted
                    int n = tensorCount(row);
                    if (SourceList.isResolved(row)) {
                        say(n == 0 ? "Resolved '" + name + "': no readable tensors"
                                : "Resolved '" + name + "': " + n + " tensor" + (n == 1 ? "" : "s"));
                    } else {
                        fail(name, "the server left it " + (TreeModelBuilder.unresolvedState(row)
                                == TreeModelBuilder.Unresolved.PENDING ? "pending" : "unresolved ("
                                        + (row.get("unresolved_reason") == null ? "no reason given"
                                                : row.get("unresolved_reason")) + ")"));
                    }
                } catch (ExecutionException e) {
                    Throwable why = e.getCause() == null ? e : e.getCause();
                    if (why instanceof IllegalStateException) { // the connection was replaced under us: drop quietly
                        say(" ");
                    } else if (why instanceof ResolveCancelledException) {
                        say("Cancelled resolving '" + name + "'");
                    } else {
                        LOG.log(Level.FINE, "Resolve failed", why);
                        fail(name, why.getMessage() != null ? why.getMessage() : why.getClass().getSimpleName());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private static Map<String, Object> currentRow(SourceList l, String id) {
        Map<String, Object> d = l.getSources().get(id);
        return d == null ? Collections.<String, Object>singletonMap("is_resolved", true) : d; // gone: nothing to resolve
    }

    private String nameOf(SourceList l, String id) {
        List<String> parts = TreeModelBuilder.pathParts(String.valueOf(currentRow(l, id).get("source_url")));
        return parts.isEmpty() ? id : parts.get(parts.size() - 1);
    }

    /** Reports a failed resolve in the status line and, as napari does, in an error dialog (through the listeners). */
    private void fail(String name, String why) {
        say("Resolve failed for '" + name + "': " + why);
        for (TensorBrowserListener l : listeners) {
            l.resolveFailed("Resolve failed", "Could not resolve '" + name + "': " + why);
        }
    }

    /** Images the source holds: its label sets ride under them and are not tensors to count. */
    private static int tensorCount(Map<String, Object> row) {
        return TreeModelBuilder.imageCount(row);
    }

    private static String humanBytes(long n) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = n;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? n + " B" : String.format(java.util.Locale.ROOT, "%.1f %s", v, units[u]);
    }

    /** Shows {@code text} in the status line and tells the listeners, busy while a resolve runs. */
    private void say(String text) {
        showMessage(text);
        if (resolvingId != null) {
            progressText = text;
        }
        noteTimer.stop();
        for (TensorBrowserListener l : listeners) {
            l.resolveStatus(text, resolvingId != null);
        }
    }

    /** Asks the running resolve to stop; it does at the server's next heartbeat. */
    private void cancelResolve() {
        if (resolveCancel != null) {
            resolveCancel.set(true);
            say("Cancelling… (takes effect at the next server heartbeat)");
        }
    }

    /** Stops the running resolve and forgets it, so its outcome is not shown. */
    private void forgetResolve() {
        if (resolveCancel == null) {
            return;
        }
        resolveCancel.set(true);
        noteTimer.stop();
        resolveCancel = null;
        resolvingId = null;
        cancelResolve.setVisible(false);
        for (TensorBrowserListener l : listeners) {
            l.resolveStatus(" ", false);
        }
    }

    /**
     * Replaces one source's row with its fresh descriptor, leaving the rest of the tree (open folders,
     * scroll) alone; the row stays selected. A source that resolved to nothing loses its row.
     */
    private void graftSource(String sourceId, Map<String, Object> descriptor) {
        if (searchResult != null && searchResult.containsKey(sourceId)) {
            searchResult.put(sourceId, descriptor); // a later rebuild of the search view draws from this
        }
        DefaultMutableTreeNode old = null;
        for (java.util.Enumeration<?> e = ((DefaultMutableTreeNode) model.getRoot()).preorderEnumeration();
                old == null && e.hasMoreElements();) {
            DefaultMutableTreeNode t = (DefaultMutableTreeNode) e.nextElement();
            TreeModelBuilder.Node n = TreeModelBuilder.nodeOf(t);
            if (n != null && n.getKind() == TreeModelBuilder.Kind.SOURCE && n.getKey().equals(sourceId)) {
                old = t;
            }
        }
        if (old == null) {
            return; // not on screen; the next full build draws it from the list
        }
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) old.getParent();
        int index = parent.getIndex(old);
        DefaultMutableTreeNode fresh = TreeModelBuilder.sourceNode(descriptor, TreeModelBuilder.nodeOf(old).getText());
        rebuilding = true;
        try {
            model.removeNodeFromParent(old);
            if (fresh != null) {
                model.insertNodeInto(fresh, parent, index);
                if (fresh.getChildCount() > 0) {
                    openSources.add(sourceId); // show what the resolve found
                    restoreExpansion(fresh);
                }
            }
            restoreSelection(false); // the user's selection stays; the row keeps its id, so a selected one stays selected
        } finally {
            rebuilding = false;
        }
        updateInfoPane();
    }

    // --- rendering -----------------------------------------------------------

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean exp,
                boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus);
            TreeModelBuilder.Node n = TreeModelBuilder.nodeOf(value);
            if (n == null) {
                return this;
            }
            String text = esc(n.getText());
            switch (n.getKind()) {
                case FOLDER:
                    setIcon(exp ? getOpenIcon() : getClosedIcon());
                    break;
                case NOTE:
                    setIcon(null);
                    text = "<i>" + text + "</i>";
                    if (!sel) {
                        setForeground(Color.GRAY);
                    }
                    break;
                default:
                    setIcon(UIManager.getIcon(n.getKind() == TreeModelBuilder.Kind.SOURCE
                            ? "FileView.fileIcon" : "FileView.hardDriveIcon"));
            }
            if (!n.getSuffix().isEmpty()) {
                text += " <font color='#888888'>" + esc(n.getSuffix()) + "</font>";
            }
            setText("<html>" + text + "</html>");
            setToolTipText(n.getTooltip().isEmpty() ? null : n.getTooltip());
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    /** Greyed hint text in an empty field (FlatLaf; ignored by other look and feels). */
    private static void placeholder(JTextField field, String text) {
        field.putClientProperty("JTextField.placeholderText", text);
    }

    /** The empty-tree message, or {@code null} while the tree has rows. */
    private String emptyMessage() {
        if (((DefaultMutableTreeNode) model.getRoot()).getChildCount() > 0) {
            return null;
        }
        boolean loading = list != null && list.isLazy() && list.getLevels().get("") == null;
        return EmptyState.message(list != null, connecting && !disposed, loading, searchResult != null, editor.canChange());
    }

    /** Sets the connection line (dot, text, detail tooltip) and repaints it and the tree's empty-state text. */
    private void showState(ConnectionStatus.State state, String text, String detail) {
        connectionLabel.show(state, text, detail);
        tree.repaint();
    }

    /** Shows a transient message under the connection line; a blank one hides the line. */
    private void showMessage(String text) {
        boolean blank = text == null || text.trim().isEmpty();
        status.setText(blank ? " " : text);
        status.setToolTipText(blank ? null : text);
        status.setVisible(!blank);
    }

    // --- test access ---------------------------------------------------------

    /** What the watcher's change callback does, without waiting for a poll. */
    void applySearch() { applyFilter(); }
    JButton connectButtonForTest() { return connectButton; }
    void simulateCatalogChange() { onCatalogChanged(); }

    JTree getTree() { return tree; }
    java.util.concurrent.ExecutorService getMetadataReads() { return metadataReads; }
    MetadataPane getInfoPane() { return infoPane; }
    Set<MetadataDialog> getMetadataDialogs() { return metadataDialogs; }
    JTextField getAddressField() { return address; }

    JPasswordField getTokenField() { return token; }
    JTextField getSearchField() { return search; }
    JLabel getStatusLabel() { return status; }
    ConnectionLabel getConnectionLabel() { return connectionLabel; }
    JLabel getSearchStatusLabel() { return searchStatus; }
    JButton getConnectButton() { return connectButton; }
    SourceEditor getEditorForTest() { return editor; }
    JButton getCancelResolveButton() { return cancelResolve; }
    SourceList getSourceListForTest() { return list; }
}
