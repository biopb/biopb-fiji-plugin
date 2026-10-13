package io.github.jiyuuchc.biopb.fiji;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;

/**
 * Adds and removes sources for the Tensor Browser, as the napari widget does: a local file or folder
 * dropped on the browser (or picked with the Add button) is registered with the server, and a source
 * added that way can be removed again.
 *
 * <p>Adding is possible only against a connected, local server ({@link DropPolicy}). One add runs at a
 * time and one removal at a time, each off the EDT, and an add can be cancelled (sources already
 * registered stay). A folder with many entries, or one inside OneDrive, asks first
 * ({@link CloudDrop}). Outcomes that arrive after the connection was replaced are dropped.
 *
 * <p>All state is on the event dispatch thread.
 */
final class SourceEditor {
    private static final Logger LOG = Logger.getLogger(SourceEditor.class.getName());

    /** What the editor needs of the browser it serves. Called on the EDT. */
    interface Host {
        /** The current source list, or {@code null} when not connected. */
        SourceList list();

        /** The URL dialed, or {@code null} if unknown. */
        String url();

        /** Whether a connect is running. */
        boolean connecting();

        /** The window to parent dialogs to. */
        Component parent();

        /** Shows a line in the browser's status. */
        void status(String text);

        /** The catalog changed under the tree: redraw it. */
        void catalogChanged();
    }

    /** The questions and reports shown to the user; tests replace it. */
    interface Prompts {
        boolean confirm(String title, String message, boolean defaultYes);

        void info(String title, String message);

        void error(String title, String message);
    }

    /** One running add: the flag that stops it, and what it was asked to do. */
    private static final class AddJob {
        final String path;
        final boolean cloud;
        final AtomicBoolean cancel = new AtomicBoolean();

        AddJob(String path, boolean cloud) {
            this.path = path;
            this.cloud = cloud;
        }
    }

    private final Host host;
    private final JPanel view = new JPanel(new BorderLayout(6, 0));
    private final JButton addButton = new JButton("Add folder/file…");
    private static final int MAX_HINT_LINES = 3;
    private final JTextArea hint = new JTextArea(" ") { // wraps at word boundaries instead of being cut off
        private static final long serialVersionUID = 1L;

        @Override public java.awt.Dimension getPreferredSize() {
            // The width the row gives it (what is left of the row beside the buttons) decides the line count.
            java.awt.FontMetrics fm = getFontMetrics(getFont());
            java.awt.Insets in = getInsets();
            int avail = hintWidth() - in.left - in.right;
            int lines = Math.min(MAX_HINT_LINES, TextWrap.lines(getText(), avail, fm::stringWidth));
            return new java.awt.Dimension(HINT_PREFERRED_WIDTH, lines * fm.getHeight() + in.top + in.bottom);
        }

        @Override public java.awt.Dimension getMinimumSize() { // may shrink in width, keeps the height its text needs
            return new java.awt.Dimension(0, getPreferredSize().height);
        }
    };
    private static final int HINT_PREFERRED_WIDTH = 120;

    /** The width the hint has on its row: the row minus the buttons beside it. */
    private int hintWidth() {
        int w = view.getWidth() - view.getInsets().left - view.getInsets().right;
        BorderLayout bl = (BorderLayout) view.getLayout();
        for (JComponent c : new JComponent[] {addButton, cancelButton}) {
            if (c.isVisible()) {
                w -= c.getPreferredSize().width + bl.getHgap();
            }
        }
        return w;
    }
    private final JButton cancelButton = new JButton("Cancel add");
    private Prompts prompts = new SwingPrompts();
    private java.util.function.Predicate<String> sizer = CloudDrop::exceedsEntryThreshold;
    private Supplier<File> chooser = this::chooseFile;
    private File lastDir;
    private AddJob adding;
    private Object removing; // the running removal's token
    private Object checking; // the drop being sized and confirmed (token), until its add starts or it is declined
    private final Set<String> skippedCloudPaths = new HashSet<>(); // adds that left offline files out

    SourceEditor(Host host) {
        this.host = host;
        view.add(addButton, BorderLayout.WEST);
        view.add(hint, BorderLayout.CENTER);
        view.add(cancelButton, BorderLayout.EAST);
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setFont(UIManager.getFont("Label.font"));
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setEditable(false);
        hint.setFocusable(false);
        hint.setOpaque(false);
        hint.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 4, 0, 0));
        view.addComponentListener(new java.awt.event.ComponentAdapter() {
            private int lastWidth = -1;

            @Override public void componentResized(java.awt.event.ComponentEvent e) { // the line count depends on the width
                if (view.getWidth() != lastWidth) {
                    lastWidth = view.getWidth();
                    SwingUtilities.invokeLater(view::revalidate);
                }
            }
        });
        cancelButton.setVisible(false);
        addButton.addActionListener(e -> addFromChooser());
        cancelButton.addActionListener(e -> cancelAdd());
        view.setTransferHandler(new Handler(null));
        updateHint();
    }

    /** The row with the Add button, the hint and the cancel button. */
    JPanel view() { return view; }

    /** Makes {@code components} accept dropped files as well as the editor's own row. */
    void acceptDropsOn(JComponent... components) {
        for (JComponent c : components) {
            c.setTransferHandler(new Handler(c.getTransferHandler()));
            if (c instanceof JTree) {
                ((JTree) c).setDropMode(DropMode.ON); // not the default, which moves the selection while dragging
            }
        }
    }

    // --- state ---------------------------------------------------------------

    private DropPolicy.Decision decision() {
        return decision(checking != null);
    }

    /** {@link #decision()} with the drop being checked counted as busy or not. */
    private DropPolicy.Decision decision(boolean checkBusy) {
        DropPolicy.Decision d = DropPolicy.decide(host.connecting(), host.list() != null, host.url(),
                adding != null || checkBusy);
        // One change at a time: a removal holds off an add as an add holds off a removal.
        return d.isOk() && removing != null ? new DropPolicy.Decision(false, REMOVING) : d;
    }

    /** Whether a source may be added or removed right now (the gate both use). */
    boolean canChange() { return decision().isOk(); }

    /** Shown while a removal runs. */
    static final String REMOVING = "Removing data\u2026";

    /** Refreshes the hint and the Add button from the connection state; a running add owns the hint. */
    void updateHint() {
        DropPolicy.Decision d = decision();
        addButton.setEnabled(d.isOk());
        addButton.setToolTipText(d.isOk() ? "Register a local file or folder with the server" : d.getReason());
        if (adding == null) {
            hint.setText(d.getReason());
        }
    }

    /** A new connection (or none): stops the running add, forgets both jobs, and drops what was remembered. */
    void connectionChanged() {
        if (adding != null) {
            adding.cancel.set(true);
        }
        adding = null;
        removing = null;
        checking = null;
        skippedCloudPaths.clear();
        cancelButton.setVisible(false);
        updateHint();
    }

    boolean isAdding() { return adding != null; }

    /** Whether an add is being checked or run, or a removal runs. */
    boolean isBusy() { return adding != null || checking != null || removing != null; }

    boolean isRemoving() { return removing != null; }

    void setPrompts(Prompts p) { prompts = p; }

    void setSizer(java.util.function.Predicate<String> p) { sizer = p; }

    void setChooser(Supplier<File> c) { chooser = c; }

    String hintText() { return hint.getText(); }

    JButton addButtonForTest() { return addButton; }

    JButton cancelButtonForTest() { return cancelButton; }

    // --- adding --------------------------------------------------------------

    private void addFromChooser() {
        if (!decision().isOk()) {
            return;
        }
        File f = chooser.get();
        if (f != null) {
            drop(f.getPath());
        }
    }

    private File chooseFile() {
        JFileChooser c = new JFileChooser(lastDir);
        c.setDialogTitle("Add a folder or file to the tensor server");
        c.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        c.setMultiSelectionEnabled(false);
        if (c.showOpenDialog(host.parent()) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File f = c.getSelectedFile();
        if (f != null && f.getParentFile() != null) {
            lastDir = f.getParentFile();
        }
        return f;
    }

    /**
     * Adds a dropped or picked path: checks the gate, sizes a folder off the EDT, asks about a big
     * folder and a cloud folder, and starts the add. The questions run a nested event loop, so the
     * world is checked again after.
     */
    void drop(String path) {
        SourceList l = host.list();
        if (!decision().isOk()) {
            return;
        }
        Object job = new Object();
        checking = job; // another drop or Add waits until this one has started or been declined
        updateHint();
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                return sizer.test(path); // walks the folder: not on the EDT
            }

            @Override protected void done() {
                if (checking != job) {
                    return; // another connection now
                }
                boolean large;
                try {
                    large = get();
                } catch (ExecutionException e) {
                    // The only guard against dropping a huge folder by mistake: when it cannot say, ask.
                    LOG.log(Level.WARNING, "Sizing the dropped folder failed; asking to confirm", e);
                    large = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    release(job);
                    return;
                }
                try {
                    afterSizing(l, path, large);
                } finally {
                    release(job); // adding (set by startAdd) keeps the gate if the add started
                }
            }
        }.execute();
    }

    private void release(Object job) {
        if (checking == job) {
            checking = null;
        }
        updateHint();
    }

    private void afterSizing(SourceList l, String path, boolean large) {
        if (!decision(false).isOk() || l != host.list()) {
            return;
        }
        if (large && !prompts.confirm("Add large folder?", SourceMessages.largeFolder(path), false)) {
            return; // declined scanning an oversized folder; nothing sent
        }
        String warning = CloudDrop.warning(path);
        if (warning != null && !prompts.confirm("Add source from a cloud folder?", warning, true)) {
            return;
        }
        // As in napari: a confirmed cloud folder is the user's say-so to register the offline
        // placeholders too (cloud mode on). Any other folder is told if the server passed some over,
        // and asked about cloud mode when added again (the server only takes cloud mode on a folder it
        // does not hold, so after a removal).
        boolean cloud = (warning != null)
                || (skippedCloudPaths.contains(path)
                        && prompts.confirm("Add as cloud sources?", SourceMessages.cloudRedrop(path), false));
        if (l != host.list() || !decision(false).isOk()) {
            return;
        }
        startAdd(l, path, cloud);
    }

    private void startAdd(SourceList l, String path, boolean cloud) {
        AddJob job = new AddJob(path, cloud);
        adding = job;
        cancelButton.setVisible(true);
        cancelButton.setEnabled(true);
        addButton.setEnabled(false);
        hint.setText("Adding " + CloudDrop.displayName(path) + "…");
        new SwingWorker<SourceChanges.AddResult, SourceChanges.AddProgress>() {
            @Override protected SourceChanges.AddResult doInBackground() throws Exception {
                return l.add(path, cloud, this::publish, job.cancel::get);
            }

            @Override protected void process(List<SourceChanges.AddProgress> chunks) {
                if (adding == job && !job.cancel.get()) {
                    hint.setText(SourceMessages.progress(chunks.get(chunks.size() - 1)));
                }
            }

            @Override protected void done() {
                if (adding != job) {
                    return; // another connection now: the outcome is dropped
                }
                adding = null;
                cancelButton.setVisible(false);
                try {
                    addDone(job, get());
                } catch (ExecutionException e) {
                    Throwable why = e.getCause() == null ? e : e.getCause();
                    LOG.log(Level.FINE, "Add failed", why);
                    updateHint();
                    reportFailure("Add data failed", message(why));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateHint();
                }
            }
        }.execute();
    }

    private void addDone(AddJob job, SourceChanges.AddResult r) {
        if (job.cloud) {
            skippedCloudPaths.remove(job.path); // done, not failed: a refused cloud add is still worth asking again
        }
        if (r.changedCatalog()) {
            host.catalogChanged();
        }
        host.status(SourceMessages.addSummary(r));
        updateHint();
        if (!r.getFailed().isEmpty()) {
            prompts.error("Some items were not added", SourceMessages.addFailures(r.getFailed()));
        }
        if (!job.cloud && (r.getSkippedOffline() > 0 || r.getSkippedCloudDirs() > 0)) {
            skippedCloudPaths.add(job.path);
            prompts.info("Some files were not added",
                    SourceMessages.skipped(job.path, r.getSkippedOffline(), r.getSkippedCloudDirs()));
        }
    }

    /** Asks the running add to stop; sources already registered stay. */
    void cancelAdd() {
        if (adding != null) {
            adding.cancel.set(true);
            cancelButton.setEnabled(false);
            hint.setText("Cancelling…");
        }
    }

    // --- removing ------------------------------------------------------------

    /** Asks, then unregisters the {@code dnd://} root {@code rootUrl}, shown as {@code displayName}. */
    void remove(String rootUrl, String displayName) {
        DropPolicy.Decision d = decision(); // the same gate as an add, and not while one runs
        if (!d.isOk()) {
            host.status("Cannot remove now: " + d.getReason());
            return;
        }
        SourceList l = host.list();
        if (!prompts.confirm("Remove dropped source?", SourceMessages.confirmRemove(displayName, rootUrl), false)
                || l != host.list() || !decision().isOk()) {
            return;
        }
        Object job = new Object();
        removing = job;
        updateHint();
        host.status("Removing " + displayName + "…");
        new SwingWorker<SourceChanges.RemoveResult, Void>() {
            @Override protected SourceChanges.RemoveResult doInBackground() throws Exception {
                return l.remove(rootUrl);
            }

            @Override protected void done() {
                if (removing != job) {
                    return; // another connection now
                }
                removing = null;
                updateHint();
                try {
                    SourceChanges.RemoveResult r = get();
                    if (!r.getRemoved().isEmpty()) {
                        host.catalogChanged();
                    }
                    host.status(SourceMessages.removeSummary(r.getRemoved().size()));
                    if (!r.getFailed().isEmpty()) {
                        prompts.error("Some sources were not removed", SourceMessages.removeFailures(r.getFailed()));
                    }
                } catch (ExecutionException e) {
                    Throwable why = e.getCause() == null ? e : e.getCause();
                    LOG.log(Level.FINE, "Remove failed", why);
                    reportFailure("Remove failed", message(why));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void reportFailure(String title, String message) {
        host.status(title + ": " + message);
        prompts.error(title, message);
    }

    private static String message(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    // --- dropping ------------------------------------------------------------

    /**
     * Takes exactly one dropped local file or folder, copied (a move would let the source delete it).
     * Only a drop: a clipboard paste is not an add. What the component did before (copying a tree row,
     * dragging it out) is handed to the handler it had.
     */
    private final class Handler extends TransferHandler {
        private final TransferHandler previous;

        Handler(TransferHandler previous) {
            this.previous = previous;
        }

        @Override public int getSourceActions(JComponent c) {
            return previous == null ? NONE : previous.getSourceActions(c);
        }

        // createTransferable and exportDone are protected and run inside the previous handler's own
        // exportToClipboard / exportAsDrag, which are forwarded whole, so they are the previous handler's.
        @Override public void exportAsDrag(JComponent c, java.awt.event.InputEvent e, int action) {
            if (previous != null) {
                previous.exportAsDrag(c, e, action);
            }
        }

        @Override public void exportToClipboard(JComponent c, java.awt.datatransfer.Clipboard clip, int action)
                throws IllegalStateException {
            if (previous != null) {
                previous.exportToClipboard(c, clip, action);
            }
        }

        @Override public boolean canImport(TransferSupport s) {
            if (!s.isDrop() || !s.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || !decision().isOk()) {
                return false;
            }
            if ((s.getSourceDropActions() & COPY) == 0) {
                return false;
            }
            s.setDropAction(COPY);
            List<File> files = files(s.getTransferable());
            return files == null || files.size() == 1; // some platforms do not say what is dragged until it lands
        }

        @Override public boolean importData(TransferSupport s) {
            if (!canImport(s)) {
                return false;
            }
            List<File> files = files(s.getTransferable());
            if (files == null || files.size() != 1) {
                return false; // one item per drop; a multi-select is refused
            }
            String path = files.get(0).getPath();
            // After the drag ends: the questions are modal and must not hold the drag source.
            SwingUtilities.invokeLater(() -> drop(path));
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    static List<File> files(Transferable t) {
        try {
            return (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
        } catch (java.awt.datatransfer.UnsupportedFlavorException | java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    // --- dialogs -------------------------------------------------------------

    private final class SwingPrompts implements Prompts {
        @Override public boolean confirm(String title, String message, boolean defaultYes) {
            if (GraphicsEnvironment.isHeadless()) {
                return false;
            }
            Object[] options = {"Yes", "No"};
            return JOptionPane.showOptionDialog(host.parent(), message, title, JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, options, options[defaultYes ? 0 : 1]) == 0;
        }

        @Override public void info(String title, String message) {
            show(title, message, JOptionPane.INFORMATION_MESSAGE);
        }

        @Override public void error(String title, String message) {
            show(title, message, JOptionPane.ERROR_MESSAGE);
        }

        private void show(String title, String message, int type) {
            if (GraphicsEnvironment.isHeadless()) {
                return; // the status line has it
            }
            JTextArea text = new JTextArea(message, Math.min(12, message.length() / 60 + 2), 60);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setEditable(false);
            text.setOpaque(false);
            JOptionPane.showMessageDialog(host.parent(), new JScrollPane(text), title, type);
        }
    }
}
