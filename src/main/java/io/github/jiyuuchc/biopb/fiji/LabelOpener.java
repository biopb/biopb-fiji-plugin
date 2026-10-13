package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.TensorDescriptor;
import biopb.tensor.TensorFlightClient;
import ij.measure.Calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * What {@link TensorOpener} needs to open a label set (an id that {@link LabelId} recognises) as a
 * label image: the set's alignment with its image, and the image's calibration for it.
 *
 * <p>A set opens like any tensor (lazy, planes read on demand; uint8/uint16 stay 8/16-bit, every
 * other dtype becomes float32, so ids above 16 bits are coerced and above 2^24 lose precision), but
 * its axes, plane grid and calibration are its image's when it is bound to it. Unlike napari, which
 * can lay a Labels layer over an image, ImageJ cannot overlay a lazy stack, so the set is a separate
 * image titled with the set's name.
 */
final class LabelOpener {
    private static final Logger LOG = Logger.getLogger(LabelOpener.class.getName());

    private LabelOpener() { }

    /** A set bound (or not) to its image. */
    static final class Bound {
        final LabelAlignment alignment;
        /** The image's descriptor; null when the set is not aligned. */
        final TensorDescriptor image;
        /** The set's name, for the title. */
        final String name;

        Bound(LabelAlignment alignment, TensorDescriptor image, String name) {
            this.alignment = alignment;
            this.image = image;
            this.name = name;
        }

        /** Wraps {@code src} so it has the plane grid of the image (a no-op when it already does). */
        PlaneSource gridded(PlaneSource src) {
            if (src.getSizeC() == alignment.sizeC && src.getSizeZ() == alignment.sizeZ
                    && src.getSizeT() == alignment.sizeT) {
                return src;
            }
            return new BroadcastPlaneSource(src, alignment.sizeC, alignment.sizeZ, alignment.sizeT);
        }

        /**
         * The scale hint of what is displayed, in the set's axes: {@code hint} (the set's own level) times
         * the image level a native-level set matched, so it is relative to the image's level 0 as a
         * {@link TensorOpener.LevelInfo} must be. Null when the set is bound but the hint does not fit its
         * axes or the image has no such level; {@code hint} itself when the set is not bound.
         */
        long[] levelHint(long[] hint) {
            if (!alignment.aligned) {
                return hint;
            }
            AxisMap im = alignment.imageMap;
            if (hint.length != alignment.labelMap.ndim || hint.length != im.ndim - (im.s >= 0 ? 1 : 0)) {
                return null;
            }
            if (alignment.imageLevel == 0) {
                return hint;
            }
            if (alignment.imageLevel >= image.getPyramidCount()) {
                return null;
            }
            List<Long> h = image.getPyramid(alignment.imageLevel).getScaleHintList();
            if (h.size() != im.ndim) {
                return null;
            }
            long[] out = new long[hint.length];
            for (int i = 0, j = 0; i < im.ndim; i++) {
                if (i != im.s) {
                    out[j] = hint[j] * h.get(i);
                    j++;
                }
            }
            return out;
        }

        /**
         * Fills {@code cal} from the image when aligned (the set is on the image's grid), else from
         * the set's own descriptor. {@code hint} is the set's level scale hint, in the set's axes. A
         * bound set whose hint does not fit is left uncalibrated (logged) rather than given the set's
         * own scales, which are not the displayed grid's; an unbound set with a hint that does not fit
         * is left uncalibrated too.
         */
        void calibrate(Calibration cal, TensorDescriptor label, long[] hint) {
            if (!alignment.aligned) {
                if (hint.length == alignment.labelMap.ndim) {
                    TensorOpener.applyCalibration(cal, alignment.labelMap, label.getPhysicalScaleList(),
                            label.getPhysicalUnitList(), hint);
                }
                return;
            }
            long[] lh = levelHint(hint);
            if (lh == null) {
                LOG.warning("Label set " + label.getArrayId() + ": scale hint does not fit its image; not calibrated");
                return;
            }
            AxisMap im = alignment.imageMap;
            long[] full = new long[im.ndim]; // the image's axes: 1 where the set has no samples axis
            for (int i = 0, j = 0; i < full.length; i++) {
                full[i] = i == im.s ? 1 : lh[j++];
            }
            TensorOpener.applyCalibration(cal, im, image.getPhysicalScaleList(), image.getPhysicalUnitList(), full);
        }
    }

    /**
     * The name a set goes by in a row or title: its name, then {@code /level} for a set stored as a
     * native pyramid level ({@code name/1} is a different tensor from {@code name}).
     */
    static String displayName(LabelId.Address a) {
        return a.level == null ? a.name : a.name + "/" + a.level;
    }

    /**
     * The title of an opened set: its name (and native level), its image's short name (two images can
     * each have a set of the same name), then the pyramid level opened when it is not 0.
     */
    static String title(String arrayId, int level) {
        LabelId.Address a = LabelId.parse(arrayId);
        String t = a == null ? arrayId : displayName(a) + " (" + TreeModelBuilder.shortName(a.imageArrayId) + ")";
        return level == 0 ? t : t + " [level " + level + "]";
    }

    private static final java.util.Set<String> NOTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Tells {@code note} once per set and reason: switching levels re-opens the set but not the news. */
    static void noteOnce(String arrayId, String note, java.util.function.Consumer<String> out) {
        if (NOTED.size() > 256) {
            NOTED.clear();
        }
        if (NOTED.add(arrayId + "|" + note)) {
            out.accept(note);
        }
    }

    /** Forgets what {@link #noteOnce} has said (for tests). */
    static void forgetNotes() { NOTED.clear(); }

    /**
     * Decides how {@code d} (the set's descriptor) lines up with its image. An image that cannot be
     * described, or a set that does not line up, is not a failure: the set opens with its own axes
     * and a note is logged, as the napari widget does. Blocking (one descriptor read): {@link TensorOpener#open}
     * already requires a worker thread, and the controller calls it from a SwingWorker.
     *
     * @throws AxisMappingException if the set is unaligned and has no usable axes of its own
     */
    static Bound bind(TensorFlightClient client, String arrayId, TensorDescriptor d, String axisOverride,
                      java.util.function.Consumer<String> note) {
        LabelId.Address a = LabelId.parse(arrayId);
        TensorDescriptor image = null;
        try {
            image = client.getDescriptor(a.imageArrayId);
        } catch (RuntimeException e) {
            LOG.warning("Label set " + arrayId + ": image " + a.imageArrayId + " could not be described");
        }
        List<long[]> shapes = null;
        if (image != null) {
            shapes = new ArrayList<>();
            shapes.add(toArray(image.getShapeList()));
            for (int k = 1; k < image.getPyramidCount(); k++) {
                shapes.add(toArray(image.getPyramid(k).getShapeList()));
            }
        }
        LabelAlignment al = LabelAlignment.decideLevels(image == null ? null : image.getDimLabelsList(), shapes,
                d.getDimLabelsList(), toArray(d.getShapeList()), axisOverride);
        if (!al.aligned) {
            noteOnce(arrayId, al.note, note);
        }
        return new Bound(al, al.aligned ? image : null, a.name);
    }

    private static long[] toArray(List<Long> l) {
        return TensorOpener.toArray(new ArrayList<>(l));
    }
}
