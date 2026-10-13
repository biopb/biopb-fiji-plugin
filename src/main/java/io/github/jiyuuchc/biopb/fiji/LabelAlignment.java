package io.github.jiyuuchc.biopb.fiji;

import java.util.List;

/**
 * How a label set lines up with its image, decided from the two descriptors alone (no server, no
 * display). Mirrors the napari widget's {@code _label_binding} / {@code _label_rank_matches} /
 * {@code align_label_levels}: a set has the image's axes in the image's order, the channel axis a
 * singleton, and an interleaved RGB samples axis left out. So the set lines up with the image by
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
    /** Plane-grid sizes the opened stack should have (the image's when aligned, else the set's own). */
    public final int sizeC, sizeZ, sizeT;

    private LabelAlignment(boolean aligned, String note, AxisMap labelMap, AxisMap imageMap, int c, int z, int t) {
        this.aligned = aligned;
        this.note = note;
        this.labelMap = labelMap;
        this.imageMap = imageMap;
        this.sizeC = c;
        this.sizeZ = z;
        this.sizeT = t;
    }

    /**
     * @param imageLabels dim labels of the image, or null if the image could not be described
     * @param imageShape  its level-0 shape, or null if it could not be described
     * @param labelLabels dim labels of the set
     * @param labelShape  the set's level-0 shape
     * @param labelOverride the user's axis override for the set, honoured only when unaligned
     * @throws AxisMappingException if the set itself has no usable axes (unaligned case only)
     */
    public static LabelAlignment decide(List<String> imageLabels, long[] imageShape, List<String> labelLabels,
                                        long[] labelShape, String labelOverride) {
        String why;
        if (imageShape == null) {
            why = "its image could not be described";
        } else {
            why = tryAlign(imageLabels, imageShape, labelLabels, labelShape);
            if (why == null) {
                AxisMap im = AxisMap.resolve(imageLabels, imageShape, null);
                AxisMap lm = AxisMap.resolve(labelLabels, labelShape, labelRoles(im));
                return new LabelAlignment(true, null, lm, im, size(imageShape, im.c), size(imageShape, im.z),
                        size(imageShape, im.t));
            }
        }
        AxisMap lm = AxisMap.resolve(labelLabels, labelShape, labelOverride);
        return new LabelAlignment(false, "Label set is not bound to its image's axes (" + why
                + "); opening it with its own axes", lm, null, size(labelShape, lm.c), size(labelShape, lm.z),
                size(labelShape, lm.t));
    }

    /** The role string a set uses: the image's, less the samples axis (which the set does not have). */
    static String labelRoles(AxisMap image) {
        String r = image.roles();
        return image.s >= 0 ? r.substring(0, image.s) + r.substring(image.s + 1) : r;
    }

    /** Null if the set lines up, else why it does not. */
    private static String tryAlign(List<String> imageLabels, long[] imageShape, List<String> labelLabels,
                                   long[] labelShape) {
        AxisMap im;
        try {
            im = AxisMap.resolve(imageLabels, imageShape, null);
        } catch (IllegalArgumentException e) {
            return "its image's axes are ambiguous";
        }
        int wantRank = imageShape.length - (im.s >= 0 ? 1 : 0);
        if (labelShape.length != wantRank) {
            return "rank " + labelShape.length + " where its image has " + wantRank;
        }
        AxisMap lm;
        try {
            lm = AxisMap.resolve(labelLabels, labelShape, labelRoles(im));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (labelShape[lm.x] != imageShape[im.x] || labelShape[lm.y] != imageShape[im.y]) {
            return "y/x extent " + labelShape[lm.y] + "x" + labelShape[lm.x] + " differs from its image's "
                    + imageShape[im.y] + "x" + imageShape[im.x];
        }
        int[][] pairs = {{lm.t, im.t}, {lm.c, im.c}, {lm.z, im.z}};
        String[] names = {"t", "c", "z"};
        for (int i = 0; i < 3; i++) {
            long l = pairs[i][0] >= 0 ? labelShape[pairs[i][0]] : 1, m = pairs[i][1] >= 0 ? imageShape[pairs[i][1]] : 1;
            if (l != m && l != 1) {
                return names[i] + " length " + l + " is neither its image's " + m + " nor 1";
            }
        }
        return null;
    }

    private static int size(long[] shape, int axis) {
        return axis >= 0 ? (int) shape[axis] : 1;
    }

    @Override
    public String toString() {
        return aligned ? "aligned" : note;
    }
}
