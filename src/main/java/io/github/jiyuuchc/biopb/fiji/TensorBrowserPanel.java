package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.TensorFlightClient;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
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

    /** Whether {@code url}'s host is this machine ({@code localhost}, {@code 127.*}, {@code ::1}); no DNS lookup. */
    static boolean isLoopback(String url) {
        String host = url.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "").replaceFirst("(?::\\d+)?(?:/.*)?$", "");
        host = host.replaceAll("^\\[|\\]$", "");
        return host.equalsIgnoreCase("localhost") || host.matches("127(\\.\\d{1,3}){3}") || host.equals("::1");
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
    private final JTextField address = new JTextField(18);
    private final JPasswordField token = new JPasswordField(10);
    private final JButton connectButton = new JButton("Connect");
    private final JLabel status = new JLabel(" ");
    private final JTextField search = new JTextField();
    private final JLabel searchStatus = new JLabel(" ");
    private final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree tree = new JTree(model);
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
    private java.util.function.Consumer<String> errorDialog = this::showConnectError;
    private final Runnable syncToken = () -> {
        boolean discover = address.getText().trim().isEmpty();
        token.setEnabled(connectButton.isEnabled() && !discover);
        token.setToolTipText(discover
                ? "Not used when the address is empty: discovery finds the token itself (BIOPB_TENSOR_TOKEN or the control plane)"
                : "Bearer token, sent only to the address typed here (refused over plaintext to a non-local host)");
    };
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
            status.setText(progressText);
        }
    });
    private final JButton cancelResolve = new JButton("Cancel resolve");
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
        super(new BorderLayout(0, 4));
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

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(new JLabel("Server"));
        bar.add(address);
        bar.add(new JLabel("Token"));
        bar.add(token);
        bar.add(connectButton);
        cancelResolve.setVisible(false);
        bar.add(cancelResolve);
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.add(bar, BorderLayout.NORTH);
        top.add(status, BorderLayout.CENTER);
        search.setToolTipText("Search sources by id, URL or type (on the server)");
        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(search, BorderLayout.NORTH);
        south.add(searchStatus, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

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
            status.setText(e.getMessage());
            return;
        }
        char[] typed = token.getPassword();
        String secret = typed.length == 0 || discover ? null : new String(typed).trim();
        java.util.Arrays.fill(typed, '\0');
        String tokenArg = secret == null || secret.isEmpty() ? null : secret;
        if (tokenArg != null && !isTls(url) && !isLoopback(url)) {
            String msg = "Not sending the token to " + describe(url) + ": it would cross the network unencrypted. "
                    + "Use grpc+tls://" + url.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "") + " (or clear the Token field).";
            status.setText(msg);
            SwingUtilities.invokeLater(() -> {
                if (!disposed) {
                    errorDialog.accept(msg);
                }
            });
            return;
        }
        String target = discover ? "the discovered server" : describe(url);
        int gen = ++connectGen;
        connectButton.setEnabled(false);
        address.setEnabled(false);
        token.setEnabled(false);
        status.setText("Connecting to " + target + "…");
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
                if (!disposed) { // however it ended, the controls are usable again
                    connectButton.setEnabled(true);
                    address.setEnabled(true);
                    syncToken.run();
                }
                Object[] r;
                try {
                    r = get();
                } catch (Exception e) {
                    if (gen == connectGen) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        String why = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
                        status.setText(connection == null ? "Connection failed: " + why
                                : "Connection to " + target + " failed (" + why
                                        + "); still connected to " + connectionName);
                        SwingUtilities.invokeLater(() -> {
                            if (!disposed && gen == connectGen) { // not from a closed panel or a stale connect
                                errorDialog.accept(why);
                            }
                        });
                    }
                    return;
                }
                Connection c = (Connection) r[0];
                SourceList l = (SourceList) r[1];
                if (gen != connectGen) { // superseded, or the panel was disposed
                    release(c, l);
                    return;
                }
                install(c, l, c.url() != null ? describe(c.url()) : url != null ? describe(url) : "the discovered server");
            }
        }.execute();
    }

    /** Replaces the dialog shown when a connect fails (tests; the default is a message dialog). EDT. */
    void setErrorDialog(java.util.function.Consumer<String> dialog) { errorDialog = dialog; }

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
        forgetResolve();
        connectGen++;
        searchGen++;
        inFlight = false;
        debounce.stop();
        Thread closing = release(connection, list);
        connection = null;
        list = null;
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

    private void install(Connection c, SourceList l, String name) {
        forgetResolve();
        release(connection, list);
        connection = c;
        connectionName = name;
        list = l;
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
        status.setText("Connected to " + name + (l.isLazy() ? "" : " • " + l.getSources().size() + " sources"));
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
        if (menu != null) {
            menu.show(tree, e.getX(), e.getY());
        }
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
            menu.add(item("Load ROIs", () -> fireRois(id)));
        } else {
            menu.add(item("Open all " + ids.size() + " tensors", () -> {
                for (String id : new ArrayList<>(ids)) {
                    fireOpen(id, 0);
                }
            }));
        }
        return menu;
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
            status.setText("Already resolving '" + (resolvingId.equals(sourceId) ? name : nameOf(l, resolvingId)) + "'");
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

    private static int tensorCount(Map<String, Object> row) {
        Object t = row.get("tensors");
        return t instanceof java.util.Collection ? ((java.util.Collection<?>) t).size() : 0;
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
        status.setText(text);
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

    // --- test access ---------------------------------------------------------

    /** What the watcher's change callback does, without waiting for a poll. */
    void applySearch() { applyFilter(); }
    JButton connectButtonForTest() { return connectButton; }
    void simulateCatalogChange() { onCatalogChanged(); }

    JTree getTree() { return tree; }
    JTextField getAddressField() { return address; }

    JPasswordField getTokenField() { return token; }
    JTextField getSearchField() { return search; }
    JLabel getStatusLabel() { return status; }
    JLabel getSearchStatusLabel() { return searchStatus; }
    JButton getConnectButton() { return connectButton; }
    JButton getCancelResolveButton() { return cancelResolve; }
    SourceList getSourceListForTest() { return list; }
}
