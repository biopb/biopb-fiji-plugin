package io.github.jiyuuchc.biopb.fiji;

import java.util.Collections;
import java.util.List;

/**
 * How a label set lines up with its image, decided from the two descriptors alone (no server, no
 * display). Mirrors the napari widget's {@code _label_binding} / {@code _label_rank_matches} /
 * {@code align_label_levels}: a set has the image's axes in the image's order and an interleaved RGB
 * samples axis left out; each of t, c, z is the image's length or a singleton (typically the channel
 * axis). So the set lines up with the image by
 * position once its rank is checked, and a singleton axis whose image length is greater than 1 is
 * <b>broadcast</b>.
 *
 * <p>napari does not refuse a set that does not line up: it adds it at its own rank and warns. This
 * does the same: {@link #aligned} is false, {@link #note} says why, and the set opens with its own
 * axes and calibration.
 */
public final class LabelAlignment {
    /** Whether the set is bound to its image's axes and grid. */
    public final boolean aligned;
    /** Why not (for the log), or null when aligned. */
    public final String note;
    /** The set's own axis map (in the set's rank). */
    public final AxisMap labelMap;
    /** The image's axis map, or null when unaligned. */
    public final AxisMap imageMap;
    /** Which image level (0 = full) the set matched in size; 0 for a set at the image's full size. */
    public final int imageLevel;
    /** Plane-grid sizes the opened stack should have (the image's when aligned, else the set's own). */
    public final int sizeC, sizeZ, sizeT;

    private LabelAlignment(boolean aligned, String note, AxisMap labelMap, AxisMap imageMap, int imageLevel, int c,
                           int z, int t) {
        this.aligned = aligned;
        this.note = note;
        this.labelMap = labelMap;
        this.imageMap = imageMap;
        this.imageLevel = imageLevel;
        this.sizeC = c;
        this.sizeZ = z;
        this.sizeT = t;
    }

    /** As {@link #decideLevels} with only the image's level-0 shape known. */
    public static LabelAlignment decide(List<String> imageLabels, long[] imageShape, List<String> labelLabels,
                                        long[] labelShape, String labelOverride) {
        return decideLevels(imageLabels, imageShape == null ? null : Collections.singletonList(imageShape), labelLabels,
                labelShape, labelOverride);
    }

    /**
     * @param imageLabels dim labels of the image, or null if the image could not be described
     * @param imageShapes the image's shape at level 0 first, then at each coarser pyramid level; null
     *                    if the image could not be described. A set that is a native level (id
     *                    {@code .../@labels/name/level}) is the size of one of them, so the set
     *                    binds if it matches any
     * @param labelLabels dim labels of the set
     * @param labelShape  the set's own shape
     * @param labelOverride the user's axis override for the set: used for the set's own axes when
     *                    unbound, and also tried for the image's axes when the image has no
     *                    unambiguous ones (the set shares them)
     * @throws AxisMappingException only when the set is not bound and has no usable axes of its own,
     *                    which is the open's own failure (the caller asks for an override); a bind
     *                    problem never throws
     */
    public static LabelAlignment decideLevels(List<String> imageLabels, List<long[]> imageShapes, List<String> labelLabels,
                                        long[] labelShape, String labelOverride) {
        String why;
        if (imageShapes == null || imageShapes.isEmpty()) {
            why = "its image could not be described";
        } else {
            why = null;
            for (int k = 0; k < imageShapes.size(); k++) {
                Object[] r = tryAlign(imageLabels, imageShapes.get(k), labelLabels, labelShape, labelOverride);
                if (r[0] == null) {
                    AxisMap im = (AxisMap) r[1], lm = (AxisMap) r[2];
                    long[] shape = imageShapes.get(k);
                    return new LabelAlignment(true, null, lm, im, k, size(shape, im.c), size(shape, im.z),
                            size(shape, im.t));
                }
                if (k == 0) {
                    why = (String) r[0];
                }
            }
        }
        AxisMap lm = AxisMap.resolve(labelLabels, labelShape, labelOverride);
        return new LabelAlignment(false, "Label set is not bound to its image's axes (" + why
                + "); opening it with its own axes", lm, null, 0, size(labelShape, lm.c), size(labelShape, lm.z),
                size(labelShape, lm.t));
    }

    /** The role string a set uses: the image's, less the samples axis (which the set does not have). */
    static String labelRoles(AxisMap image) {
        String r = image.roles();
        return image.s >= 0 ? r.substring(0, image.s) + r.substring(image.s + 1) : r;
    }

    /** {@code {why-not or null, image map, label map}}; never throws. */
    private static Object[] tryAlign(List<String> imageLabels, long[] imageShape, List<String> labelLabels,
                                     long[] labelShape, String labelOverride) {
        AxisMap im;
        try {
            im = AxisMap.resolve(imageLabels, imageShape, null);
        } catch (IllegalArgumentException e) {
            im = null;
            if (labelOverride != null && labelOverride.trim().length() == imageShape.length) {
                try { // the set shares its image's axes, so the override that fits the set may fit the image
                    im = AxisMap.resolve(imageLabels, imageShape, labelOverride);
                } catch (IllegalArgumentException ignored) {
                    // falls through to "ambiguous"
                }
            }
            if (im == null) {
                return new Object[] {"its image's axes are ambiguous", null, null};
            }
        }
        int wantRank = imageShape.length - (im.s >= 0 ? 1 : 0);
        if (labelShape.length != wantRank) {
            return new Object[] {"rank " + labelShape.length + " where its image has " + wantRank, null, null};
        }
        AxisMap lm;
        try {
            lm = AxisMap.resolve(labelLabels, labelShape, labelRoles(im));
        } catch (IllegalArgumentException e) {
            return new Object[] {e.getMessage(), null, null};
        }
        if (labelShape[lm.x] != imageShape[im.x] || labelShape[lm.y] != imageShape[im.y]) {
            return new Object[] {"y/x extent " + labelShape[lm.y] + "x" + labelShape[lm.x]
                    + " differs from its image's " + imageShape[im.y] + "x" + imageShape[im.x], null, null};
        }
        int[][] pairs = {{lm.t, im.t}, {lm.c, im.c}, {lm.z, im.z}};
        String[] names = {"t", "c", "z"};
        for (int i = 0; i < 3; i++) {
            long l = pairs[i][0] >= 0 ? labelShape[pairs[i][0]] : 1, m = pairs[i][1] >= 0 ? imageShape[pairs[i][1]] : 1;
            if (l != m && l != 1) {
                return new Object[] {names[i] + " length " + l + " is neither its image's " + m + " nor 1", null, null};
            }
        }
        return new Object[] {null, im, lm};
    }

    private static int size(long[] shape, int axis) {
        return axis >= 0 ? (int) shape[axis] : 1;
    }

    @Override
    public String toString() {
        return aligned ? "aligned" : note;
    }
}
