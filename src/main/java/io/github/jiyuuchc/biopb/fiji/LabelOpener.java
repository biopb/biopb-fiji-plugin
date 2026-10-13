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
         * Fills {@code cal} from the image when aligned (the set is on the image's grid), else from
         * the set's own descriptor. {@code hint} is the set's level scale hint, in the set's axes.
         */
        void calibrate(Calibration cal, TensorDescriptor label, long[] hint) {
            if (!alignment.aligned) {
                TensorOpener.applyCalibration(cal, alignment.labelMap, label.getPhysicalScaleList(),
                        label.getPhysicalUnitList(), hint);
                return;
            }
            AxisMap im = alignment.imageMap;
            long[] full = new long[im.ndim]; // the image's axes: 1 where the set has no samples axis
            for (int i = 0, j = 0; i < full.length; i++) {
                full[i] = i == im.s ? 1 : hint[j++];
            }
            TensorOpener.applyCalibration(cal, im, image.getPhysicalScaleList(), image.getPhysicalUnitList(), full);
        }
    }

    /**
     * Decides how {@code d} (the set's descriptor) lines up with its image. An image that cannot be
     * described, or a set that does not line up, is not a failure: the set opens with its own axes
     * and a note is logged, as the napari widget does. Blocking (one descriptor read).
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
        LabelAlignment al = LabelAlignment.decide(image == null ? null : image.getDimLabelsList(),
                image == null ? null : toArray(image.getShapeList()), d.getDimLabelsList(),
                toArray(d.getShapeList()), axisOverride);
        if (!al.aligned) {
            note.accept(al.note);
        }
        return new Bound(al, al.aligned ? image : null, a.name);
    }

    private static long[] toArray(List<Long> l) {
        return TensorOpener.toArray(new ArrayList<>(l));
    }
}
