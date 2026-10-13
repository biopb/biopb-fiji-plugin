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
import javax.swing.JLabel;
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
    private final JLabel hint = new JLabel(" ");
    private final JButton cancelButton = new JButton("Cancel add");
    private Prompts prompts = new SwingPrompts();
    private Supplier<File> chooser = this::chooseFile;
    private File lastDir;
    private AddJob adding;
    private Object removing; // the running removal's token
    private final Set<String> skippedCloudPaths = new HashSet<>(); // adds that left offline files out

    SourceEditor(Host host) {
        this.host = host;
        view.add(addButton, BorderLayout.WEST);
        view.add(hint, BorderLayout.CENTER);
        view.add(cancelButton, BorderLayout.EAST);
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        cancelButton.setVisible(false);
        addButton.addActionListener(e -> addFromChooser());
        cancelButton.addActionListener(e -> cancelAdd());
        view.setTransferHandler(new Handler());
        updateHint();
    }

    /** The row with the Add button, the hint and the cancel button. */
    JPanel view() { return view; }

    /** Makes {@code components} accept dropped files as well as the editor's own row. */
    void acceptDropsOn(JComponent... components) {
        for (JComponent c : components) {
            c.setTransferHandler(new Handler());
            if (c instanceof JTree) {
                ((JTree) c).setDropMode(DropMode.ON); // not the default, which moves the selection while dragging
            }
        }
    }

    // --- state ---------------------------------------------------------------

    private DropPolicy.Decision decision() {
        return DropPolicy.decide(host.connecting(), host.list() != null, host.url(), adding != null);
    }

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
        skippedCloudPaths.clear();
        cancelButton.setVisible(false);
        updateHint();
    }

    boolean isAdding() { return adding != null; }

    boolean isRemoving() { return removing != null; }

    void setPrompts(Prompts p) { prompts = p; }

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
     * Adds a dropped or picked path: checks the gate, asks about a big folder and a cloud folder,
     * and starts the add. The questions run a nested event loop, so the world is checked again after.
     */
    void drop(String path) {
        SourceList l = host.list();
        if (!decision().isOk()) {
            return;
        }
        if (CloudDrop.exceedsEntryThreshold(path)
                && !prompts.confirm("Add large folder?", SourceMessages.largeFolder(path), false)) {
            return; // declined scanning an oversized folder; nothing sent
        }
        String warning = CloudDrop.warning(path);
        if (warning != null && !prompts.confirm("Add source from a cloud folder?", warning, true)) {
            return;
        }
        // A confirmed cloud folder is the user's say-so to register the offline placeholders too. Any
        // other folder is told if the server passed some over, and asked about cloud mode when added
        // again (the server only takes cloud mode on a folder it does not hold, so after a removal).
        boolean cloud = warning != null || skippedCloudPaths.contains(path)
                && prompts.confirm("Add as cloud sources?", SourceMessages.cloudRedrop(path), false);
        if (l != host.list() || !decision().isOk()) {
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
        if (removing != null || host.list() == null) {
            return;
        }
        SourceList l = host.list();
        if (!prompts.confirm("Remove dropped source?", SourceMessages.confirmRemove(displayName), false)
                || l != host.list() || removing != null) {
            return;
        }
        Object job = new Object();
        removing = job;
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

    /** Takes exactly one dropped local file or folder, copied (a move would let the source delete it). */
    private final class Handler extends TransferHandler {
        @Override public boolean canImport(TransferSupport s) {
            if (!s.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || !decision().isOk()) {
                return false;
            }
            if (s.isDrop()) {
                if ((s.getSourceDropActions() & COPY) == 0) {
                    return false;
                }
                s.setDropAction(COPY);
            }
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
