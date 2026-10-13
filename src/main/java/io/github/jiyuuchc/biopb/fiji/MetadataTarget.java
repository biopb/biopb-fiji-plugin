package io.github.jiyuuchc.biopb.fiji;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What the metadata dialog and the pane under the tree say about the selected source or tensor,
 * read from the source's catalog row. No Swing, so it is tested without a display.
 */
public final class MetadataTarget {
    private final String sourceId;
    private final String arrayId;
    private final String path;
    private final String shape;
    private final String dtype;
    private final String dims;
    private final boolean tensor;

    private MetadataTarget(String sourceId, String arrayId, String path, String shape, String dtype, String dims,
            boolean tensor) {
        this.tensor = tensor;
        this.sourceId = sourceId;
        this.arrayId = arrayId;
        this.path = path;
        this.shape = shape;
        this.dtype = dtype;
        this.dims = dims;
    }

    /**
     * Describes {@code row}'s tensor {@code arrayId}; with {@code arrayId == null} the tensor is the
     * source's only one, if it has just one (a source with several, or none, has no tensor line).
     *
     * @return the target, or {@code null} if the row is missing or has no source id
     * @param row a {@code sources} row ({@code source_id}, {@code source_url}, {@code tensors})
     */
    public static MetadataTarget of(Map<String, Object> row, String arrayId) {
        Object rawId = row == null ? null : row.get("source_id");
        if (rawId == null || rawId.toString().trim().isEmpty()) {
            return null; // nothing to ask the server about
        }
        String id = rawId.toString();
        List<Map<?, ?>> tensors = tensors(row);
        Map<?, ?> t = null;
        for (Map<?, ?> c : tensors) {
            if (arrayId != null ? arrayId.equals(String.valueOf(c.get("array_id")))
                    : tensors.size() == 1) {
                t = c;
                break;
            }
        }
        Object url = row.get("source_url");
        List<String> parts = url == null ? java.util.Collections.<String>emptyList()
                : TreeModelBuilder.pathParts(url.toString());
        String path = parts.isEmpty() ? id : "/" + String.join("/", parts);
        if (t == null) {
            return new MetadataTarget(id, arrayId, path, "", "", "N/A", false);
        }
        return new MetadataTarget(id, String.valueOf(t.get("array_id")), path, shape(t.get("shape")),
                t.get("dtype") == null ? "" : t.get("dtype").toString(), dims(t.get("dim_labels")), true);
    }

    private static List<Map<?, ?>> tensors(Map<String, Object> row) {
        List<Map<?, ?>> out = new ArrayList<>();
        Object raw = row.get("tensors");
        if (raw instanceof Collection) {
            for (Object o : (Collection<?>) raw) {
                if (o instanceof Map && ((Map<?, ?>) o).get("array_id") != null) {
                    out.add((Map<?, ?>) o);
                }
            }
        }
        return out;
    }

    /** The full shape as {@code 1×2×3} (not squeezed, unlike a tree row); empty if unknown. */
    static String shape(Object raw) {
        StringBuilder sb = new StringBuilder();
        if (raw instanceof Collection) {
            for (Object d : (Collection<?>) raw) {
                sb.append(sb.length() == 0 ? "" : "×").append(d);
            }
        }
        return sb.toString();
    }

    private static String dims(Object raw) {
        StringBuilder sb = new StringBuilder();
        if (raw instanceof Collection) {
            for (Object d : (Collection<?>) raw) {
                sb.append(sb.length() == 0 ? "" : ", ").append(d);
            }
        }
        return sb.length() == 0 ? "N/A" : sb.toString();
    }

    /** A window title naming the source (and the tensor, when it is not just the source's only one). */
    public String getTitle() {
        List<String> parts = TreeModelBuilder.pathParts(path);
        String name = parts.isEmpty() ? sourceId : parts.get(parts.size() - 1);
        String array = arrayId == null ? "" : TreeModelBuilder.shortName(arrayId);
        return "Metadata - " + name + (array.isEmpty() || array.equals(name) ? "" : " / " + array);
    }

    /** The source id. */
    public String getSourceId() { return sourceId; }
    /** The tensor's array id, or {@code null} when the target is a source with no single tensor. */
    public String getArrayId() { return arrayId; }
    /** Whether a tensor was found (so shape, dtype and dims mean something). */
    public boolean hasTensor() { return tensor; }
    /** The source's path as {@code /a/b/c.zarr}, or its id when it has no URL. */
    public String getPath() { return path; }
    /** The shape, {@code 3×512×512}; empty if no tensor. */
    public String getShape() { return shape; }
    /** The dtype; empty if no tensor. */
    public String getDtype() { return dtype; }
    /** The axis labels joined by commas, or {@code N/A}. */
    public String getDims() { return dims; }

    /** The pane's lines under the identifiers: shape, dtype, dims. */
    public List<String> summaryLines() {
        List<String> l = new ArrayList<>();
        l.add("Shape: " + shape);
        l.add("Dtype: " + dtype);
        l.add("Dims: " + dims);
        return l;
    }
}
