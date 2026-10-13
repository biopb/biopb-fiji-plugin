package io.github.jiyuuchc.biopb.fiji;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the metadata dialog shows of a source's metadata, as the napari widget does: empty entries
 * dropped, the rest as indented JSON-style text. No Swing, so it is tested without a display.
 */
public final class MetadataFormat {
    /** Shown when nothing is left to show. */
    public static final String NONE = "No metadata available";
    /** Most characters of metadata text shown; a text area cannot sensibly hold more. */
    public static final int MAX_CHARS = 200_000;

    private MetadataFormat() { }

    /**
     * Whether {@code value} is empty for display: {@code null}, a blank string, or a collection, map
     * or array that is empty or holds only empty values (recursively).
     */
    public static boolean isEmptyForDisplay(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence) {
            return value.toString().trim().isEmpty();
        }
        if (value instanceof Map) {
            for (Object v : ((Map<?, ?>) value).values()) {
                if (!isEmptyForDisplay(v)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof Collection) {
            for (Object v : (Collection<?>) value) {
                if (!isEmptyForDisplay(v)) {
                    return false;
                }
            }
            return true;
        }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                if (!isEmptyForDisplay(Array.get(value, i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** {@code metadata} without its empty top-level entries (order kept); empty for {@code null}. */
    public static Map<String, Object> filterEmpty(Map<String, ?> metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, ?> e : metadata.entrySet()) {
                if (!isEmptyForDisplay(e.getValue())) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    /** The text of the dialog's metadata box: filtered, indented by two spaces, or {@link #NONE}. */
    public static String toText(Map<String, ?> metadata) {
        Map<String, Object> filtered = filterEmpty(metadata);
        if (filtered.isEmpty()) {
            return NONE;
        }
        StringBuilder sb = new StringBuilder();
        write(sb, filtered, 0);
        return truncate(sb.toString(), MAX_CHARS);
    }

    /** {@code text} cut at a line end within {@code max} characters, with a note of how much is not shown. */
    static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf('\n', max);
        if (cut <= 0) {
            cut = max;
        }
        int more = 0;
        for (int i = cut; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                more++;
            }
        }
        return text.substring(0, cut) + "\n… " + more + " more lines (" + (text.length() - cut)
                + " characters) not shown";
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                indent(sb, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append('}');
        } else if (v instanceof Collection || v.getClass().isArray()) {
            Object[] items = items(v);
            if (items.length == 0) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    sb.append(",\n");
                }
                indent(sb, depth + 1);
                write(sb, items[i], depth + 1);
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append(']');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v); // NaN and Infinity print as such, as Python's json.dumps does
        } else {
            quote(sb, v.toString());
        }
    }

    private static Object[] items(Object v) {
        if (v instanceof Collection) {
            return ((Collection<?>) v).toArray();
        }
        Object[] out = new Object[Array.getLength(v)];
        for (int i = 0; i < out.length; i++) {
            out[i] = Array.get(v, i);
        }
        return out;
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
