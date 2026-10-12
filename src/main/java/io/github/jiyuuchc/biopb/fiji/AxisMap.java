package io.github.jiyuuchc.biopb.fiji;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which tensor axis (index in descriptor order) plays which role. Roles are t, c, z, y, x and s
 * (sample, e.g. RGB); {@code -1} means the role is absent. Axes that play no role are singletons.
 *
 * <p>This class owns the label alias table ({@link #roleOf}); {@link PlaneMap} uses it too.
 * Policy everywhere: two axes with the same role are an {@link IllegalArgumentException}.
 */
public final class AxisMap {
    private static final Pattern UNLABELED = Pattern.compile("dim\\d+", Pattern.CASE_INSENSITIVE);
    private static final String ROLES = "tczyxs";

    /** Axis index per role, or -1. */
    public final int t, c, z, y, x, s;
    /** Number of tensor axes. */
    public final int ndim;

    private AxisMap(int[] r, int ndim) {
        t = r[0]; c = r[1]; z = r[2]; y = r[3]; x = r[4]; s = r[5];
        this.ndim = ndim;
    }

    /**
     * Resolves roles for a tensor.
     *
     * @param labels   dimension labels from the descriptor (may be empty)
     * @param shape    tensor shape in descriptor order
     * @param override optional role string, one character per axis in descriptor order, from
     *                 {@code t c z y x s} or {@code -} (axis ignored, must be a singleton); e.g.
     *                 {@code "tczyx"}. Required when more than two axes are unlabeled and non-singleton.
     * @return the resolved map
     * @throws IllegalArgumentException if the axes cannot be mapped unambiguously
     */
    public static AxisMap resolve(List<String> labels, long[] shape, String override) {
        int n = shape.length;
        int[] r = {-1, -1, -1, -1, -1, -1};
        if (n < 2) {
            throw new IllegalArgumentException("Tensor has " + n + " dimension(s); at least y and x are required");
        }
        if (override != null && !override.trim().isEmpty()) {
            String o = override.trim().toLowerCase(Locale.ROOT);
            if (o.length() != n) {
                throw new IllegalArgumentException("Axis override \"" + override + "\" has " + o.length()
                        + " characters but the tensor has " + n + " axes");
            }
            for (int d = 0; d < n; d++) assign(r, o.substring(d, d + 1), d, shape);
        } else if (isUnlabeled(labels)) {
            for (int d = 0; d < n - 2; d++) {
                if (shape[d] != 1) {
                    throw new IllegalArgumentException("Tensor has " + n + " unlabeled axes (shape "
                            + Arrays.toString(shape) + "); only the last two (y, x) can be assumed. "
                            + "Pass an axis override with one role letter per axis (t c z y x s -), e.g. \""
                            + "tczyx".substring(Math.max(0, 5 - n)) + "\"");
                }
            }
            r[3] = n - 2;
            r[4] = n - 1;
        } else {
            for (int d = 0; d < n; d++) {
                String l = d < labels.size() ? labels.get(d).trim() : "";
                String role = roleOf(l);
                if (role == null) {
                    if (shape[d] != 1) {
                        throw new IllegalArgumentException("Unsupported axis \"" + l + "\" (size " + shape[d]
                                + "). Only t, c, z, y, x and s are supported (at most 5 non-singleton dimensions); "
                                + "use an axis override to name it");
                    }
                    role = "-";
                }
                assign(r, role, d, shape);
            }
        }
        if (r[3] < 0 || r[4] < 0) {
            throw new IllegalArgumentException("Could not find both y and x axes in labels " + labels);
        }
        long czt = 1;
        for (int a : new int[] {r[0], r[1], r[2], r[3], r[4]}) {
            if (a < 0) continue;
            if (shape[a] > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Axis " + a + " has size " + shape[a] + ", too large for ImageJ");
            }
        }
        for (int a : new int[] {r[0], r[1], r[2]}) if (a >= 0) czt *= shape[a];
        if (czt > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("c*z*t = " + czt + " planes exceeds ImageJ's stack limit of 2^31-1");
        }
        if (r[5] >= 0 && shape[r[5]] == 1) r[5] = -1;
        if (r[5] >= 0 && shape[r[5]] != 3) {
            throw new IllegalArgumentException("Sample axis has size " + shape[r[5]] + "; only 3 (RGB) is supported");
        }
        if (r[5] >= 0 && r[1] >= 0 && shape[r[1]] > 1) {
            throw new IllegalArgumentException("RGB (sample axis) with " + shape[r[1]] + " channels is not supported: "
                    + "ImageJ cannot make a composite of RGB images. Open a single channel by dropping the channel axis");
        }
        return new AxisMap(r, n);
    }

    private static void assign(int[] r, String role, int d, long[] shape) {
        if (role.equals("-")) {
            if (shape[d] != 1) {
                throw new IllegalArgumentException("Axis " + d + " is ignored but has size " + shape[d]);
            }
            return;
        }
        int i = ROLES.indexOf(role);
        if (i < 0) throw new IllegalArgumentException("Unknown axis role \"" + role + "\"; use one of t c z y x s -");
        if (r[i] >= 0) {
            throw new IllegalArgumentException("Axis role '" + role + "' assigned twice (axes " + r[i] + " and " + d + ")");
        }
        r[i] = d;
    }

    /**
     * The single alias table for axis labels (case-insensitive, trimmed): t/time, c/ch/channel,
     * z/depth, y, x, s/sample.
     *
     * @return the role letter (one of {@code t c z y x s}) or null if the label names no role
     */
    public static String roleOf(String label) {
        if (label == null) return null;
        switch (label.trim().toLowerCase(Locale.ROOT)) {
            case "t": case "time": return "t";
            case "c": case "channel": case "ch": return "c";
            case "z": case "depth": return "z";
            case "y": return "y";
            case "x": return "x";
            case "s": case "sample": return "s";
            default: return null;
        }
    }

    /** Role letter per axis in descriptor order ({@code '-'} = no role), e.g. {@code "tczyx"}. */
    public String roles() {
        char[] r = new char[ndim];
        Arrays.fill(r, '-');
        int[] idx = {t, c, z, y, x, s};
        for (int i = 0; i < 6; i++) if (idx[i] >= 0) r[idx[i]] = ROLES.charAt(i);
        return new String(r);
    }

    private static boolean isUnlabeled(List<String> labels) {
        if (labels == null || labels.isEmpty()) return true;
        for (String l : labels) if (!UNLABELED.matcher(l.trim()).matches()) return false;
        return true;
    }
}
