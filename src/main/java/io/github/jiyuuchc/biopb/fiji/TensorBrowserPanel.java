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
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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
 * <p>Threading: all state lives on the event dispatch thread. Every server call
 * (connect, read a folder, search) runs on a {@link SwingWorker}; the
 * {@link SourceList} change callback, which arrives on its watcher thread, is
 * marshalled to the EDT. {@link SourceList} reads the open folders through a
 * volatile snapshot kept up to date on the EDT.
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

        @Override
        void close();
    }

    /** Opens connections; the seam tests replace. */
    public interface ClientFactory {
        /** Connects to a server (called off the EDT). */
        Connection connect(String host, int port) throws IOException;
    }

    /** The real factory: a {@link TensorFlightClient} read through a {@link FlightCatalogClient}. */
    public static ClientFactory flightFactory() {
        return (host, port) -> {
            TensorFlightClient flight = new TensorFlightClient(host, port);
            FlightCatalogClient catalog = new FlightCatalogClient(flight);
            return new Connection() {
                @Override public CatalogClient catalog() { return catalog; }
                @Override public TensorFlightClient flight() { return flight; }
                @Override public void close() { flight.close(); }
            };
        };
    }

    /** The address to start with: {@code BIOPB_TENSOR_URL} if {@code env} has it, else {@link #DEFAULT_ADDRESS}. */
    public static String defaultAddress(Function<String, String> env) {
        String v = env.apply("BIOPB_TENSOR_URL");
        return v == null || v.trim().isEmpty() ? DEFAULT_ADDRESS : v.trim();
    }

    /** {@code [host, port]} of {@code host[:port]} (a leading scheme such as {@code grpc://} is dropped). */
    static Object[] parseAddress(String address) {
        String a = address.trim().replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "").replaceAll("/+$", "");
        int colon = a.lastIndexOf(':');
        if (a.isEmpty()) {
            throw new IllegalArgumentException("Enter host:port");
        }
        if (colon < 0) {
            return new Object[] {a, DEFAULT_PORT};
        }
        try {
            return new Object[] {a.substring(0, colon), Integer.parseInt(a.substring(colon + 1))};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad port in '" + address + "'");
        }
    }

    private final ClientFactory factory;
    private final List<TensorBrowserListener> listeners = new CopyOnWriteArrayList<>();
    private final JTextField address = new JTextField(18);
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
    private SourceList list;
    private int connectGen;
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

    /** A panel connecting through {@link #flightFactory()}, starting at {@code BIOPB_TENSOR_URL}. */
    public TensorBrowserPanel() {
        this(flightFactory(), defaultAddress(System::getenv));
    }

    /**
     * @param factory opens connections
     * @param initialAddress the address field's initial text
     */
    public TensorBrowserPanel(ClientFactory factory, String initialAddress) {
        super(new BorderLayout(0, 4));
        this.factory = factory;
        address.setText(initialAddress);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(0); // double-click opens; the handles expand
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(new JLabel("Server"));
        bar.add(address);
        bar.add(connectButton);
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
        address.addActionListener(e -> connect());
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
                    if (n != null && n.isOpenable()) {
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

    /** The current Flight client, or {@code null} if not connected (or a test double). Call on the EDT. */
    public TensorFlightClient getClient() { return connection == null ? null : connection.flight(); }

    /** Connects to the address in the field (asynchronous); a no-op while the field is invalid. */
    public void connect() {
        Object[] hp;
        try {
            hp = parseAddress(address.getText());
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            return;
        }
        String host = (String) hp[0];
        int port = (Integer) hp[1];
        int gen = ++connectGen;
        connectButton.setEnabled(false);
        status.setText("Connecting to " + host + ":" + port + "…");
        new SwingWorker<Object[], Void>() {
            @Override protected Object[] doInBackground() throws Exception {
                Connection c = factory.connect(host, port);
                SourceList l = new SourceList(c.catalog());
                try {
                    l.refresh();
                } catch (Exception | Error e) {
                    l.close();
                    c.close();
                    throw e;
                }
                return new Object[] {c, l};
            }

            @Override protected void done() {
                Object[] r;
                try {
                    r = get();
                } catch (Exception e) {
                    if (gen == connectGen) {
                        connectButton.setEnabled(true);
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        status.setText("Connection failed: " + cause.getMessage());
                    }
                    return;
                }
                Connection c = (Connection) r[0];
                SourceList l = (SourceList) r[1];
                if (gen != connectGen) { // superseded, or the panel was disposed
                    release(c, l);
                    return;
                }
                connectButton.setEnabled(true);
                install(c, l, host + ":" + port);
            }
        }.execute();
    }

    /** Stops the watcher and closes the connection; the panel is not used again. */
    public void dispose() {
        connectGen++;
        searchGen++;
        debounce.stop();
        release(connection, list);
        connection = null;
        list = null;
    }

    // --- connection ----------------------------------------------------------

    private static void release(Connection c, SourceList l) {
        if (l != null) {
            l.close();
        }
        if (c != null) { // closing a channel can block; keep it off the EDT
            Thread t = new Thread(c::close, "biopb-close");
            t.setDaemon(true);
            t.start();
        }
    }

    private void install(Connection c, SourceList l, String name) {
        release(connection, list);
        connection = c;
        list = l;
        searchGen++;
        searchResult = null;
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
            if (list == l) {
                applyFilter();
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
            return;
        }
        String key = search.getText().trim().toLowerCase();
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
        if (n != null && n.getKind() == TreeModelBuilder.Kind.FOLDER && openSet().contains(n.getKey())) {
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
        if (rebuilding || n == null || n.getKind() != TreeModelBuilder.Kind.FOLDER) {
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
        int gen = ++searchGen;
        SourceList l = list;
        Connection c = connection;
        String q = search.getText().trim().toLowerCase();
        if (l == null) {
            return;
        }
        if (q.isEmpty()) {
            searchResult = null;
            searchStatus.setText(" ");
            rebuild();
            return;
        }
        searchStatus.setText("Server-side search • Searching…");
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
                try {
                    Object[] r = get();
                    searchResult = (Map<String, Map<String, Object>>) r[0];
                    searchStatus.setText("Server-side search • " + searchResult.size() + " match"
                            + (searchResult.size() == 1 ? "" : "es")
                            + ((Boolean) r[1] ? " • first " + TreeModelBuilder.SEARCH_LIMIT
                                    + " shown, refine the search" : ""));
                    searchOpen = new HashSet<>();
                    searchOpen.addAll(TreeModelBuilder.topFolders(
                            TreeModelBuilder.buildPaths(searchResult.values()), 1));
                    rebuild();
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Search failed", e);
                    searchStatus.setText("Search failed • the server did not answer");
                }
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

    JTree getTree() { return tree; }
    JTextField getAddressField() { return address; }
    JTextField getSearchField() { return search; }
    JLabel getStatusLabel() { return status; }
    JLabel getSearchStatusLabel() { return searchStatus; }
    JButton getConnectButton() { return connectButton; }
}
