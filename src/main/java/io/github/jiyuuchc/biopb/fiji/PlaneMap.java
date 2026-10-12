package io.github.jiyuuchc.biopb.fiji;

import ij.ImagePlus;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
     * Derive the mapping from the tensor's {@code dim_labels}: labels {@code t}/{@code time},
     * {@code c}/{@code ch}/{@code channel}, {@code z}/{@code depth} (case-insensitive; null
     * labels ignored). The first axis with a given meaning wins.
     */
    public static PlaneMap fromDimLabels(List<String> labels) {
        Map<Integer, Dim> m = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i) == null) {
                continue;
            }
            Dim d = null;
            switch (labels.get(i).toLowerCase(Locale.ROOT)) {
                case "t": case "time":
                    d = Dim.T;
                    break;
                case "c": case "ch": case "channel":
                    d = Dim.C;
                    break;
                case "z": case "depth":
                    d = Dim.Z;
                    break;
                default:
            }
            if (d != null && !m.containsValue(d)) {
                m.put(i, d);
            }
        }
        return new PlaneMap(m);
    }

    /**
     * The {@code [c, z, t]} position (1-based, 0 = all) for a pin against the image's sizes.
     *
     * @throws RoiConverter.SkipException when a pin is on an unmapped axis or outside the image
     */
    public int[] position(Map<Integer, Integer> plane, int nC, int nZ, int nT) {
        int[] pos = new int[3];
        int[] size = {nC, nZ, nT};
        for (Map.Entry<Integer, Integer> e : plane.entrySet()) {
            Dim d = axes.get(e.getKey());
            if (d == null) {
                throw new RoiConverter.SkipException("pinned axis is not t/c/z");
            }
            int idx = e.getValue();
            if (idx < 0 || idx >= size[d.ordinal()]) {
                throw new RoiConverter.SkipException("plane outside image");
            }
            pos[d.ordinal()] = idx + 1;
        }
        return pos;
    }

    /** Apply a {@link #position} result to a Roi, as a hyperstack c/z/t or a stack index. */
    static void place(ij.gui.Roi roi, int[] pos, ImagePlus imp) {
        if (pos[0] == 0 && pos[1] == 0 && pos[2] == 0) {
            return;
        }
        if (imp.isHyperStack()) {
            roi.setPosition(pos[0], pos[1], pos[2]);
        } else {
            // Plain stack: one varying dimension; unpinned size-1 dimensions are index 1.
            roi.setPosition(imp.getStackIndex(Math.max(1, pos[0]), Math.max(1, pos[1]),
                    Math.max(1, pos[2])));
        }
    }
}
