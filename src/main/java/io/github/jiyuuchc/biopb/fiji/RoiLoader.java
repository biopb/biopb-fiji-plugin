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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Loads a tensor's ROI annotations for an open {@link ImagePlus}, in two steps:
 * {@link #load} fetches and converts (blocking, no UI mutation, safe on a worker thread) and
 * {@link Result#applyTo} puts the ROIs on the image (EDT only).
 */
public final class RoiLoader {
    private RoiLoader() {
    }

    /** Outcome of a load. */
    public static final class Result {
        /** ROIs converted. */
        public final int loaded;
        /** Annotations not converted. */
        public final int skipped;
        /** Skip counts by reason (e.g. {@code unsupported ROI shape 'mask'}). */
        public final Map<String, Integer> skippedReasons;
        /** True when the server's per-tensor cap clipped the set (the SDK has no paging). */
        public final boolean truncated;
        /** The converted ROIs. */
        public final List<Roi> rois;

        Result(List<Roi> rois, Map<String, Integer> reasons, int skipped, boolean truncated) {
            this.rois = Collections.unmodifiableList(rois);
            this.loaded = rois.size();
            this.skipped = skipped;
            this.skippedReasons = reasons;
            this.truncated = truncated;
        }

        private static String key(Roi r) {
            String id = r.getProperty(RoiConverter.PROP_ROI_ID);
            return id == null ? null : id + "\0" + r.getProperty(RoiConverter.PROP_SET_NAME);
        }

        /**
         * Add the ROIs to {@code imp}'s overlay (existing ROIs kept), skipping any whose
         * ({@code biopb.roi_id}, {@code biopb.set_name}) is already in the overlay. Also adds
         * them to the ROI Manager when requested and not headless. <b>EDT only.</b>
         *
         * @return the number of ROIs added
         */
        public int applyTo(ImagePlus imp, boolean useRoiManager) {
            Overlay ov = imp.getOverlay() != null ? imp.getOverlay() : new Overlay();
            Set<String> have = new HashSet<>();
            for (Roi r : ov.toArray()) {
                String k = key(r);
                if (k != null) {
                    have.add(k);
                }
            }
            List<Roi> added = new ArrayList<>();
            for (Roi r : rois) {
                String k = key(r);
                if (k == null || have.add(k)) {
                    ov.add((Roi) r.clone());
                    added.add(r);
                }
            }
            if (added.isEmpty()) {
                return 0;
            }
            imp.setOverlay(ov);
            if (useRoiManager && !GraphicsEnvironment.isHeadless()) {
                RoiManager rm = RoiManager.getInstance2() != null ? RoiManager.getInstance2()
                        : new RoiManager();
                for (Roi r : added) {
                    rm.addRoi((Roi) r.clone());
                }
            }
            return added.size();
        }
    }

    /** Against a live client. */
    public static Result load(TensorFlightClient client, String arrayId, String setName,
            ImagePlus imp, PlaneMap planes, double sx, double sy) throws IOException {
        return load(RoiSource.of(client), arrayId, setName, imp, planes, sx, sy);
    }

    /**
     * Fetch and convert annotations; does not modify {@code imp} (only reads its dimensions).
     * Blocking: call from a worker thread.
     *
     * @param setName set to read; empty for the client-owned sets
     * @param planes how tensor axes map to c/z/t, see {@link PlaneMap}. ROIs pinned outside the
     *        image or on a non-c/z/t axis are skipped; unpinned ones show on all planes. On a
     *        non-hyperstack the pin becomes the stack index.
     * @param sx level-0 to view scale factor in x (see {@link RoiConverter} for the mapping)
     * @param sy same for y
     */
    public static Result load(RoiSource source, String arrayId, String setName, ImagePlus imp,
            PlaneMap planes, double sx, double sy) throws IOException {
        RoiListResult res = source.list(arrayId, setName);
        List<Roi> rois = new ArrayList<>();
        Map<String, Integer> reasons = new TreeMap<>();
        int skipped = 0;
        for (RoiAnnotation a : res.getRoisList()) {
            try {
                Roi r = RoiConverter.toRoi(a, sx, sy);
                PlaneMap.place(r, planes.position(a.getPlaneMap(), imp.getNChannels(),
                        imp.getNSlices(), imp.getNFrames()), imp);
                rois.add(r);
            } catch (RoiConverter.SkipException e) {
                skipped++;
                reasons.merge(e.reason(), 1, Integer::sum);
            } catch (RuntimeException e) {
                skipped++;
                reasons.merge("malformed annotation (" + e.getClass().getSimpleName() + ")", 1,
                        Integer::sum);
            }
        }
        return new Result(rois, reasons, skipped, res.getTruncated());
    }
}
