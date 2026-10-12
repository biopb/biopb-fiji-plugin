package io.github.jiyuuchc.biopb.fiji;

import biopb.image.RoiAnnotation;
import biopb.image.RoiListResult;
import biopb.tensor.TensorFlightClient;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads a tensor's ROI annotations onto an open {@link ImagePlus}.
 *
 * <p>{@code load} blocks on the network: call it from a worker thread, never the EDT.
 */
public final class RoiLoader {
    private RoiLoader() {
    }

    /** Outcome of a load. */
    public static final class Result {
        /** ROIs added. */
        public final int loaded;
        /** Annotations not added. */
        public final int skipped;
        /** Skip counts by reason (e.g. {@code unsupported ROI shape 'mask'}). */
        public final Map<String, Integer> skippedReasons;
        /** True when the server's per-tensor cap clipped the set (the SDK has no paging). */
        public final boolean truncated;
        /** The ROIs added. */
        public final List<Roi> rois;

        Result(List<Roi> rois, Map<String, Integer> reasons, int skipped, boolean truncated) {
            this.rois = rois;
            this.loaded = rois.size();
            this.skipped = skipped;
            this.skippedReasons = reasons;
            this.truncated = truncated;
        }
    }

    /** Against a live client, overlay only (no ROI Manager). */
    public static Result load(TensorFlightClient client, String arrayId, String setName,
            ImagePlus imp, PlaneMap planes, double sx, double sy) throws IOException {
        return load(RoiSource.of(client), arrayId, setName, imp, planes, sx, sy, false);
    }

    /**
     * Fetch, convert and add annotations to {@code imp}'s overlay (existing overlay ROIs are kept).
     *
     * @param setName set to read; empty for the client-owned sets
     * @param planes how tensor axes map to c/z/t, see {@link PlaneMap}; ROIs pinned outside the
     *        image are skipped, unpinned ones get position 0 (all planes)
     * @param sx level-0 to view scale factor in x (coordinates are divided by it)
     * @param sy same for y
     * @param useRoiManager also add to the ROI Manager, when not headless
     */
    public static Result load(RoiSource source, String arrayId, String setName, ImagePlus imp,
            PlaneMap planes, double sx, double sy, boolean useRoiManager) throws IOException {
        RoiListResult res = source.list(arrayId, setName);
        List<Roi> rois = new ArrayList<>();
        Map<String, Integer> reasons = new TreeMap<>();
        int skipped = 0;
        for (RoiAnnotation a : res.getRoisList()) {
            try {
                Roi r = RoiConverter.toRoi(a, sx, sy);
                int[] pos = planes.position(a.getPlaneMap(), imp.getNChannels(), imp.getNSlices(),
                        imp.getNFrames());
                if (pos == null) {
                    skipped++;
                    reasons.merge("plane outside image", 1, Integer::sum);
                    continue;
                }
                if (pos[0] != 0 || pos[1] != 0 || pos[2] != 0) {
                    r.setPosition(pos[0], pos[1], pos[2]);
                }
                rois.add(r);
            } catch (UnsupportedOperationException e) {
                skipped++;
                reasons.merge(e.getMessage().replaceAll("; supported:.*", ""), 1, Integer::sum);
            }
        }
        if (!rois.isEmpty()) {
            Overlay ov = imp.getOverlay() != null ? imp.getOverlay() : new Overlay();
            for (Roi r : rois) {
                ov.add(r);
            }
            imp.setOverlay(ov);
            if (useRoiManager && !GraphicsEnvironment.isHeadless()) {
                RoiManager rm = RoiManager.getInstance2() != null ? RoiManager.getInstance2()
                        : new RoiManager();
                for (Roi r : rois) {
                    rm.addRoi((Roi) r.clone());
                }
            }
        }
        return new Result(rois, reasons, skipped, res.getTruncated());
    }
}
