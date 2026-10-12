package io.github.jiyuuchc.biopb.fiji;

import ij.ImagePlus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the axes of a tensor to the ImagePlus dimensions C, Z and T.
 *
 * <p>An annotation's {@code plane} pin is {@code {axisIndex: index}}, both 0-based, where
 * {@code axisIndex} is a position in the tensor's own {@code dim_labels}. An axis absent from
 * the pin applies at every index. A pin on an axis that is not mapped to c/z/t (Y/X, unlabelled
 * or unknown axes) cannot be shown on the image, so the ROI is skipped rather than shown on
 * every plane. Pinned indices become 1-based positions; an unpinned dimension is 0 ("all").
 */
public final class PlaneMap {
    /** Hyperstack dimension. */
    public enum Dim { C, Z, T }

    private final Map<Integer, Dim> axes;

    private PlaneMap(Map<Integer, Dim> axes) {
        this.axes = axes;
    }

    /** Explicit mapping, tensor axis index to dimension. */
    public static PlaneMap of(Map<Integer, Dim> axes) {
        return new PlaneMap(new HashMap<>(axes));
    }

    /**
     * Derive the mapping from the tensor's {@code dim_labels} using {@link AxisMap#roleOf} (null
     * labels ignored). Two axes with the same role throw {@link IllegalArgumentException}, as in
     * {@link AxisMap}.
     */
    public static PlaneMap fromDimLabels(List<String> labels) {
        StringBuilder roles = new StringBuilder();
        for (String l : labels) {
            String r = AxisMap.roleOf(l);
            roles.append(r == null || "yxs".contains(r) ? "-" : r);
        }
        return fromRoles(roles.toString());
    }

    /** Mapping from the roles resolved for an opened image, so an axis override reaches ROI placement. */
    public static PlaneMap fromAxisMap(AxisMap map) {
        return fromRoles(map.roles());
    }

    /** From a role string (one letter per axis; only {@code t c z} are used; duplicates throw). */
    public static PlaneMap fromRoles(String roles) {
        Map<Integer, Dim> m = new HashMap<>();
        for (int i = 0; i < roles.length(); i++) {
            Dim d = null;
            switch (roles.charAt(i)) {
                case 't': d = Dim.T; break;
                case 'c': d = Dim.C; break;
                case 'z': d = Dim.Z; break;
                default:
            }
            if (d != null) {
                if (m.containsValue(d)) {
                    throw new IllegalArgumentException("Axis role " + roles.charAt(i) + " assigned twice in \"" + roles + "\"");
                }
                m.put(i, d);
            }
        }
        return new PlaneMap(m);
    }

    private int[] pins(Map<Integer, Integer> plane, double[] axisScale) {
        int[] idx = {-1, -1, -1}; // per Dim; -1 = unpinned
        for (Map.Entry<Integer, Integer> e : plane.entrySet()) {
            Dim d = axes.get(e.getKey());
            if (d == null) {
                throw new RoiConverter.SkipException("pinned axis is not t/c/z");
            }
            double sc = axisScale != null && e.getKey() < axisScale.length ? axisScale[e.getKey()] : 1;
            if (e.getValue() < 0) {
                throw new RoiConverter.SkipException("plane outside image");
            }
            idx[d.ordinal()] = (int) Math.floor(e.getValue() / sc);
        }
        return idx;
    }

    /**
     * Set a Roi's position from its pin. {@code axisScale} (aligned with the tensor's
     * dim_labels, null = all 1) is the pyramid level's downsampling per axis: a level-0 pin index
     * {@code i} becomes {@code floor(i / scale)}.
     *
     * <p>Hyperstack: pins become 1-based c/z/t, range-checked against the image; unpinned
     * dimensions are 0 (all). Plain stack (not a hyperstack): ImageJ does not know which axis
     * the stack runs along, so at most one mapped axis may be pinned at an index &gt; 0; that
     * index (+1) is the stack index, range-checked against the stack size. Pins at index 0 on the
     * other axes are accepted; a pin at index 0 alone cannot be told from "the stack axis is
     * unconstrained" and leaves the Roi on all planes. Two axes pinned &gt; 0 skip the ROI.
     *
     * @throws RoiConverter.SkipException for unmapped axes, out-of-range or conflicting pins
     */
    void place(ij.gui.Roi roi, Map<Integer, Integer> plane, ImagePlus imp, double[] axisScale) {
        int[] idx = pins(plane, axisScale);
        if (imp.isHyperStack()) {
            int[] size = {imp.getNChannels(), imp.getNSlices(), imp.getNFrames()};
            boolean any = false;
            for (int i = 0; i < 3; i++) {
                if (idx[i] >= size[i]) {
                    throw new RoiConverter.SkipException("plane outside image");
                }
                any |= idx[i] >= 0;
            }
            if (any) {
                roi.setPosition(Math.max(0, idx[0] + 1), Math.max(0, idx[1] + 1),
                        Math.max(0, idx[2] + 1));
            }
            return;
        }
        int chosen = -1;
        for (int i : idx) {
            if (i > 0) {
                if (chosen > 0) {
                    throw new RoiConverter.SkipException("conflicting pins on a plain stack");
                }
                chosen = i;
            }
        }
        if (chosen > 0) {
            if (chosen >= imp.getStackSize()) {
                throw new RoiConverter.SkipException("plane outside image");
            }
            roi.setPosition(chosen + 1);
        }
    }
}
