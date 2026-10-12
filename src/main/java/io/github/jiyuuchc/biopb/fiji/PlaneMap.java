package io.github.jiyuuchc.biopb.fiji;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the axes of a tensor to the ImagePlus hyperstack dimensions C, Z and T.
 *
 * <p>An annotation's {@code plane} pin is {@code {axisIndex: index}}, both 0-based, where
 * {@code axisIndex} is a position in the tensor's own {@code dim_labels}. An axis absent from
 * the pin applies at every index. This class says which pinned axes are c, z or t; pins on any
 * other axis (Y/X, unlabelled or unknown axes) are ignored, i.e. treated as "every plane".
 * Pinned indices become 1-based {@link ij.gui.Roi#setPosition(int, int, int)} arguments; an
 * unpinned dimension becomes 0 ("all").
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
     * {@code c}/{@code ch}/{@code channel}, {@code z}/{@code depth} (case-insensitive). The
     * first axis with a given meaning wins.
     */
    public static PlaneMap fromDimLabels(List<String> labels) {
        Map<Integer, Dim> m = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) {
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
     * The {@code [c, z, t]} position (1-based, 0 = all) for a pin, or null when a pinned index
     * does not fit the image ({@code nC/nZ/nT} are the ImagePlus sizes).
     */
    public int[] position(Map<Integer, Integer> plane, int nC, int nZ, int nT) {
        int[] pos = new int[3];
        int[] size = {nC, nZ, nT};
        for (Map.Entry<Integer, Integer> e : plane.entrySet()) {
            Dim d = axes.get(e.getKey());
            if (d == null) {
                continue;
            }
            int idx = e.getValue();
            if (idx < 0 || idx >= size[d.ordinal()]) {
                return null;
            }
            pos[d.ordinal()] = idx + 1;
        }
        return pos;
    }
}
