package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.TensorFlightClient;
import ij.ImageListener;
import ij.ImagePlus;
import ij.WindowManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.swing.SwingWorker;

/**
 * Handles the browser's open / ROI requests: reads on worker threads, UI on the EDT, errors
 * turned into dialogs through {@link Ui}. Everything that touches a server or ImageJ's windows is
 * injected, so tests need neither. EDT only.
 *
 * <p>Each opened image holds a reference to its {@link RefCountedConnection} until it is closed,
 * so closing the browser does not break images already open. Results that arrive after the
 * connection they were requested on is gone (window closed, reconnected) are dropped silently.
 */
public class TensorBrowserController implements TensorBrowserListener {
    /** ImagePlus property holding the id of the connection an image was opened through. */
    public static final String CONNECTION_PROPERTY = "biopb.connection";
    /** Most times the axis prompt is shown for one open. */
    static final int MAX_AXIS_ATTEMPTS = 3;

    /** The user-facing side; every method is called on the EDT. */
    public interface Ui {
        /** Shows progress text; {@code busy} is true while any request is outstanding. */
        void status(String text, boolean busy);
        /** Shows a finished image. */
        void show(ImagePlus imp);
        /** Asks whether to reopen at a coarser level; true to go ahead. */
        boolean confirm(String title, String message);
        /** Asks for an axis override, pre-filled with {@code previous}; null means cancelled. */
        String askAxes(String message, String previous);
        /** Shows an error or notice. */
        void error(String title, String message);
    }

    /** Opens a tensor (blocking). */
    @FunctionalInterface
    public interface Opener {
        ImagePlus open(TensorFlightClient client, String arrayId, int level, String axisOverride);
    }

    /** Loads ROIs (blocking). */
    @FunctionalInterface
    public interface RoiFetcher {
        RoiLoader.Result load(TensorFlightClient client, String arrayId, ImagePlus imp,
                TensorOpener.LevelInfo info) throws IOException;
    }

    /** The open images (EDT). */
    public interface ImageSource {
        /** The current image, or null. */
        ImagePlus current();
        /** All open images. */
        List<ImagePlus> all();
        /** Whether {@code imp} is still open. */
        boolean isOpen(ImagePlus imp);
    }

    /** Releases a reference when an image closes. */
    private static final class ReleaseOnClose implements ImageListener {
        private final ImagePlus imp;
        private final RefCountedConnection conn;
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();

        ReleaseOnClose(ImagePlus imp, RefCountedConnection conn) {
            this.imp = imp;
            this.conn = conn;
        }

        @Override public void imageOpened(ImagePlus i) { }
        @Override public void imageUpdated(ImagePlus i) { }
        @Override public void imageClosed(ImagePlus i) {
            if (i == imp) {
                release();
            }
        }

        /** Drops the image's reference, once. */
        void release() {
            if (released.compareAndSet(false, true)) {
                ImagePlus.removeImageListener(this);
                conn.close();
            }
        }
    }

    private final Supplier<RefCountedConnection> connection;
    private final Ui ui;
    private final Opener opener;
    private final RoiFetcher rois;
    private final ImageSource images;
    private final Set<String> inFlight = new HashSet<>();
    private int busy;

    /** The real wiring. */
    public TensorBrowserController(Supplier<RefCountedConnection> connection, Ui ui) {
        this(connection, ui, TensorOpener::open,
                (c, id, imp, info) -> RoiLoader.load(RoiSource.of(c), id, "", imp, info),
                new ImageSource() {
                    @Override public ImagePlus current() { return WindowManager.getCurrentImage(); }
                    @Override public List<ImagePlus> all() { return openImages(); }
                    @Override public boolean isOpen(ImagePlus imp) {
                        return WindowManager.getImage(imp.getID()) == imp;
                    }
                });
    }

    /** With every external dependency supplied. */
    public TensorBrowserController(Supplier<RefCountedConnection> connection, Ui ui, Opener opener,
            RoiFetcher rois, ImageSource images) {
        this.connection = connection;
        this.ui = ui;
        this.opener = opener;
        this.rois = rois;
        this.images = images;
    }

    private static List<ImagePlus> openImages() {
        List<ImagePlus> l = new ArrayList<>();
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) {
                    l.add(imp);
                }
            }
        }
        return l;
    }

    /**
     * The image to load ROIs onto: among images opened from {@code arrayId} through connection
     * {@code connId}, the current one, else the one at the finest level (lowest level number).
     */
    static ImagePlus pick(ImagePlus current, List<ImagePlus> all, String arrayId, String connId) {
        if (matches(current, arrayId, connId)) {
            return current;
        }
        ImagePlus best = null;
        for (ImagePlus imp : all) {
            if (matches(imp, arrayId, connId)
                    && (best == null || TensorOpener.LevelInfo.of(imp).getLevel()
                            < TensorOpener.LevelInfo.of(best).getLevel())) {
                best = imp;
            }
        }
        return best;
    }

    private static boolean matches(ImagePlus imp, String arrayId, String connId) {
        TensorOpener.LevelInfo info = TensorOpener.LevelInfo.of(imp);
        return info != null && info.getArrayId().equals(arrayId)
                && connId.equals(imp.getProperty(CONNECTION_PROPERTY));
    }

    private void status(String text) { ui.status(text, busy > 0); }

    private void begin(String text) {
        busy++;
        ui.status(text, true);
    }

    private void end() { busy = Math.max(0, busy - 1); }

    @Override
    public void openRequested(String arrayId, int level) {
        openWith(arrayId, level, null, 0);
    }

    private static String key(String arrayId, int level) { return arrayId + "@" + level; }

    private void openWith(String arrayId, int level, String axisOverride, int axisAttempts) {
        RefCountedConnection conn = connection.get();
        String what = arrayId + (level > 0 ? " [level " + level + "]" : "");
        if (conn == null || !conn.acquire()) {
            ui.error("Tensor Browser", "Not connected to a server.");
            return;
        }
        if (!inFlight.add(key(arrayId, level))) {
            conn.close();
            status(what + " is already being opened");
            return;
        }
        begin("Opening " + what + "…");
        new SwingWorker<ImagePlus, Void>() {
            @Override protected ImagePlus doInBackground() {
                ImagePlus imp = opener.open(conn.flight(), arrayId, level, axisOverride);
                imp.setProperty(CONNECTION_PROPERTY, conn.id());
                return imp;
            }

            @Override protected void done() {
                inFlight.remove(key(arrayId, level));
                end();
                ImagePlus imp = null;
                Throwable failure = null;
                try {
                    imp = get();
                } catch (CancellationException e) {
                    conn.close();
                    status("Opening " + what + " cancelled");
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    conn.close();
                    return;
                } catch (ExecutionException e) {
                    failure = e.getCause() != null ? e.getCause() : e;
                }
                if (connection.get() != conn) { // closed or reconnected meanwhile: drop the result
                    conn.close();
                    return;
                }
                if (failure != null) {
                    conn.close();
                    status("Could not open " + what);
                    onOpenFailed(arrayId, level, axisOverride, axisAttempts, failure);
                    return;
                }
                ReleaseOnClose owner = new ReleaseOnClose(imp, conn);
                try { // the image now owns the reference
                    ImagePlus.addImageListener(owner);
                    ui.show(imp);
                    status("Opened " + what);
                } catch (RuntimeException | Error e) {
                    ui.status("Could not display " + what, busy > 0);
                    ui.error("Could not display " + arrayId, OpenFailure.describe(e));
                    owner.release();
                    try {
                        imp.close();
                    } catch (RuntimeException ignored) {
                        // best effort: the window may be half built
                    }
                }
            }
        }.execute();
    }

    private void onOpenFailed(String arrayId, int level, String axisOverride, int attempts, Throwable e) {
        OpenFailure f = OpenFailure.classify(e);
        switch (f.kind) {
            case TOO_LARGE:
                if (f.suggestedLevel > level) {
                    if (ui.confirm("Plane too large",
                            f.message + "\n\nOpen pyramid level " + f.suggestedLevel + " instead?")) {
                        openWith(arrayId, f.suggestedLevel, axisOverride, 0);
                    }
                } else {
                    ui.error("Plane too large", f.message);
                }
                break;
            case NEEDS_AXES:
            case INVALID_AXES:
                if (attempts >= MAX_AXIS_ATTEMPTS) {
                    ui.error("Could not open " + arrayId, f.message);
                    break;
                }
                String o = ui.askAxes(f.message, axisOverride == null ? "" : axisOverride);
                if (o != null && !o.trim().isEmpty()) {
                    openWith(arrayId, level, o.trim(), attempts + 1);
                }
                break;
            default:
                ui.error("Could not open " + arrayId, f.message);
        }
    }

    @Override
    public void roisRequested(String arrayId) {
        RefCountedConnection conn = connection.get();
        if (conn == null || !conn.acquire()) {
            ui.error("Load ROIs", "Not connected to a server.");
            return;
        }
        List<ImagePlus> all = images.all();
        ImagePlus cur = images.current();
        ImagePlus imp = pick(cur, all, arrayId, conn.id());
        if (imp == null) {
            conn.close();
            ui.error("Load ROIs", "Open '" + arrayId + "' as an image first; ROIs are loaded onto the open image.");
            return;
        }
        int matching = 0;
        for (ImagePlus i : all) {
            if (matches(i, arrayId, conn.id())) {
                matching++;
            }
        }
        TensorOpener.LevelInfo info = TensorOpener.LevelInfo.of(imp);
        String note = matching > 1 ? " (" + matching + " images open; using the "
                + (imp == cur ? "current one" : "finest level") + ")" : "";
        String target = "'" + imp.getTitle() + "'" + note;
        begin("Loading ROIs for " + arrayId + " onto " + target + "…");
        new SwingWorker<RoiLoader.Result, Void>() {
            @Override protected RoiLoader.Result doInBackground() throws Exception {
                return rois.load(conn.flight(), arrayId, imp, info);
            }

            @Override protected void done() {
                end();
                RoiLoader.Result r = null;
                Throwable failure = null;
                try {
                    r = get();
                } catch (CancellationException e) {
                    conn.close();
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    conn.close();
                    return;
                } catch (ExecutionException e) {
                    failure = e.getCause() != null ? e.getCause() : e;
                }
                conn.close(); // the read is over
                if (connection.get() != conn) {
                    return; // stale
                }
                if (failure != null) {
                    status("Could not load ROIs");
                    ui.error("Load ROIs", OpenFailure.describe(failure));
                    return;
                }
                if (!images.isOpen(imp)) {
                    status("Image " + target + " was closed; ROIs not added");
                    return;
                }
                try {
                    int added = r.applyTo(imp, false);
                    status(summary(r, added) + " onto " + target);
                } catch (RuntimeException e) {
                    status("Could not display ROIs");
                    ui.error("Could not display ROIs", OpenFailure.describe(e));
                }
            }
        }.execute();
    }

    /** One line describing a finished ROI load. */
    static String summary(RoiLoader.Result r, int added) {
        StringBuilder sb = new StringBuilder("Loaded " + r.loaded + " ROI" + (r.loaded == 1 ? "" : "s"));
        if (added != r.loaded) {
            sb.append(" (").append(added).append(" new)");
        }
        if (r.skipped > 0) {
            sb.append(", skipped ").append(r.skipped).append(" ").append(r.skippedReasons);
        }
        if (r.truncated) {
            sb.append("; truncated by the server's ROI cap, more exist");
        }
        return sb.toString();
    }
}
