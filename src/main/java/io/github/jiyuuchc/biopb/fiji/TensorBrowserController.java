package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.TensorFlightClient;
import ij.ImagePlus;
import ij.WindowManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.SwingWorker;

/**
 * Handles the browser's open / ROI requests: reads on worker threads, UI on the EDT, errors
 * turned into dialogs through {@link Ui}. Everything that touches a server or ImageJ's windows is
 * injected, so tests need neither.
 */
public class TensorBrowserController implements TensorBrowserListener {

    /** The user-facing side; every method is called on the EDT. */
    public interface Ui {
        /** Shows progress text; {@code busy} turns the progress indicator on. */
        void status(String text, boolean busy);
        /** Shows a finished image. */
        void show(ImagePlus imp);
        /** Asks whether to reopen at a coarser level; true to go ahead. */
        boolean confirm(String title, String message);
        /** Asks for an axis override; null means cancelled. */
        String askAxes(String message);
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

    /** Lists open images (EDT); the current image first. */
    @FunctionalInterface
    public interface ImageSource {
        List<ImagePlus> images();
    }

    private final Supplier<TensorFlightClient> client;
    private final Ui ui;
    private final Opener opener;
    private final RoiFetcher rois;
    private final ImageSource images;

    /** The real wiring. */
    public TensorBrowserController(Supplier<TensorFlightClient> client, Ui ui) {
        this(client, ui, TensorOpener::open,
                (c, id, imp, info) -> RoiLoader.load(RoiSource.of(c), id, "", imp, info),
                TensorBrowserController::openImages);
    }

    /** With every external dependency supplied. */
    public TensorBrowserController(Supplier<TensorFlightClient> client, Ui ui, Opener opener,
            RoiFetcher rois, ImageSource images) {
        this.client = client;
        this.ui = ui;
        this.opener = opener;
        this.rois = rois;
        this.images = images;
    }

    private static List<ImagePlus> openImages() {
        List<ImagePlus> l = new ArrayList<>();
        ImagePlus cur = WindowManager.getCurrentImage();
        if (cur != null) {
            l.add(cur);
        }
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null && imp != cur) {
                    l.add(imp);
                }
            }
        }
        return l;
    }

    /** The first image (current one first) opened from {@code arrayId}, or null. */
    static ImagePlus pick(List<ImagePlus> candidates, String arrayId) {
        for (ImagePlus imp : candidates) {
            TensorOpener.LevelInfo info = TensorOpener.LevelInfo.of(imp);
            if (info != null && info.getArrayId().equals(arrayId)) {
                return imp;
            }
        }
        return null;
    }

    @Override
    public void openRequested(String arrayId, int level) {
        openWith(arrayId, level, null);
    }

    void openWith(String arrayId, int level, String axisOverride) {
        TensorFlightClient c = client.get();
        if (c == null) {
            ui.error("Tensor Browser", "Not connected to a server.");
            return;
        }
        String what = arrayId + (level > 0 ? " [level " + level + "]" : "");
        ui.status("Opening " + what + "…", true);
        new SwingWorker<ImagePlus, Void>() {
            @Override protected ImagePlus doInBackground() {
                return opener.open(c, arrayId, level, axisOverride);
            }

            @Override protected void done() {
                try {
                    ImagePlus imp = get();
                    ui.show(imp);
                    ui.status("Opened " + what, false);
                } catch (Exception e) {
                    ui.status("Could not open " + what, false);
                    onOpenFailed(arrayId, level, axisOverride, e);
                }
            }
        }.execute();
    }

    private void onOpenFailed(String arrayId, int level, String axisOverride, Throwable e) {
        OpenFailure f = OpenFailure.classify(e);
        switch (f.kind) {
            case TOO_LARGE:
                if (f.suggestedLevel > level && ui.confirm("Plane too large",
                        f.message + "\n\nOpen pyramid level " + f.suggestedLevel + " instead?")) {
                    openWith(arrayId, f.suggestedLevel, axisOverride);
                } else if (f.suggestedLevel <= level) {
                    ui.error("Plane too large", f.message);
                }
                break;
            case NEEDS_AXES: {
                String o = ui.askAxes(f.message);
                if (o != null && !o.trim().isEmpty()) {
                    openWith(arrayId, level, o.trim());
                }
                break;
            }
            default:
                ui.error("Could not open " + arrayId, f.message);
        }
    }

    @Override
    public void roisRequested(String arrayId) {
        List<ImagePlus> all = images.images();
        ImagePlus imp = pick(all, arrayId);
        TensorFlightClient c = client.get();
        if (imp == null) {
            ui.error("Load ROIs", "Open '" + arrayId + "' as an image first; ROIs are loaded onto the open image.");
            return;
        }
        if (c == null) {
            ui.error("Load ROIs", "Not connected to a server.");
            return;
        }
        TensorOpener.LevelInfo info = TensorOpener.LevelInfo.of(imp);
        ui.status("Loading ROIs for " + arrayId + "…", true);
        new SwingWorker<RoiLoader.Result, Void>() {
            @Override protected RoiLoader.Result doInBackground() throws Exception {
                return rois.load(c, arrayId, imp, info);
            }

            @Override protected void done() {
                try {
                    RoiLoader.Result r = get();
                    int added = r.applyTo(imp, false);
                    ui.status(summary(r, added), false);
                } catch (Exception e) {
                    ui.status("Could not load ROIs", false);
                    ui.error("Load ROIs", OpenFailure.classify(e).message);
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
