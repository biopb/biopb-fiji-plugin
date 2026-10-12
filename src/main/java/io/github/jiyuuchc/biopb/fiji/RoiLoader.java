package io.github.jiyuuchc.biopb.fiji;

import biopb.image.RoiAnnotation;
import biopb.image.RoiListResult;
import biopb.tensor.TensorFlightClient;
import ij.ImagePlus;
import ij.WindowManager;
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

        /** Dedupe key (array, set, id); null for a Roi without an id, which is never deduped. */
        private static String key(Roi r) {
            String id = r.getProperty(RoiConverter.PROP_ROI_ID);
            if (id == null || id.isEmpty()) {
                return null;
            }
            return r.getProperty(RoiConverter.PROP_ARRAY_ID) + "\0"
                    + r.getProperty(RoiConverter.PROP_SET_NAME) + "\0" + id;
        }

        /**
         * Show the ROIs: in the image's overlay (existing ROIs kept) or, when {@code
         * useRoiManager} is true and the JVM is not headless, in the ROI Manager <i>instead</i>
         * of the overlay (headless: falls back to the overlay). ROIs with an id are skipped when
         * the same (array_id, set_name, roi_id) is already in the overlay / manager. For the
         * manager, {@code imp} is made the temporary current image while adding, so call this
         * on the EDT with nothing else changing the current image. <b>EDT only.</b>
         *
         * @return the number of ROIs actually added
         */
        public int applyTo(ImagePlus imp, boolean useRoiManager) {
            boolean mgr = useRoiManager && !GraphicsEnvironment.isHeadless();
            Overlay ov = null;
            RoiManager rm = null;
            Set<String> have = new HashSet<>();
            if (mgr) {
                rm = RoiManager.getInstance2() != null ? RoiManager.getInstance2() : new RoiManager();
                for (Roi r : rm.getRoisAsArray()) {
                    String k = key(r);
                    if (k != null) {
                        have.add(k);
                    }
                }
            } else {
                ov = imp.getOverlay() != null ? imp.getOverlay() : new Overlay();
                for (Roi r : ov.toArray()) {
                    String k = key(r);
                    if (k != null) {
                        have.add(k);
                    }
                }
            }
            int n = 0;
            ImagePlus prev = WindowManager.getCurrentImage();
            try {
                if (mgr) {
                    WindowManager.setTempCurrentImage(imp);
                }
                for (Roi r : rois) {
                    String k = key(r);
                    if (k != null && !have.add(k)) {
                        continue;
                    }
                    if (mgr) {
                        rm.addRoi((Roi) r.clone());
                    } else {
                        ov.add((Roi) r.clone());
                    }
                    n++;
                }
            } finally {
                if (mgr) {
                    WindowManager.setTempCurrentImage(prev);
                }
            }
            if (!mgr && n > 0) {
                imp.setOverlay(ov);
            }
            return n;
        }
    }

    /**
     * Load for an image opened by {@link TensorOpener}: roles, level scales and the x/y factors come
     * from its {@link TensorOpener.LevelInfo}. If the roles cannot form a plane map, pinned
     * annotations are skipped (with a reason) instead of failing the load.
     */
    public static Result load(RoiSource source, String arrayId, String setName, ImagePlus imp,
            TensorOpener.LevelInfo info) throws IOException {
        PlaneMap planes;
        try {
            planes = info.toPlaneMap();
        } catch (IllegalArgumentException e) {
            planes = null;
        }
        return load(source, arrayId, setName, imp, planes, info.getAxisScale(), info.getScaleX(), info.getScaleY());
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
        return load(source, arrayId, setName, imp, planes, null, sx, sy);
    }

    /**
     * As above with a per-axis level scale ({@code axisScale[a]} for tensor axis {@code a},
     * aligned with dim_labels, null = 1) for pyramid levels that also downsample z/t/c: a
     * level-0 pin index {@code i} maps to {@code floor(i / scale)}.
     */
    public static Result load(RoiSource source, String arrayId, String setName, ImagePlus imp,
            PlaneMap planes, double[] axisScale, double sx, double sy) throws IOException {
        RoiListResult res = source.list(arrayId, setName);
        // A null plane map (e.g. its construction failed) must not abort the load: annotations
        // that need plane placement are skipped with a reason, unpinned ones still load.
        List<Roi> rois = new ArrayList<>();
        Map<String, Integer> reasons = new TreeMap<>();
        int skipped = 0;
        for (RoiAnnotation a : res.getRoisList()) {
            try {
                Roi r = RoiConverter.toRoi(a, sx, sy);
                if (planes != null) {
                    planes.place(r, a.getPlaneMap(), imp, axisScale);
                } else if (!a.getPlaneMap().isEmpty()) {
                    throw new RoiConverter.SkipException("no usable axis-to-plane mapping");
                }
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
