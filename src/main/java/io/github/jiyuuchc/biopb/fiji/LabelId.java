package io.github.jiyuuchc.biopb.fiji;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a label set's {@code array_id} says it belongs.
 *
 * <p>A label set is an ordinary tensor of its image, addressed under a marked segment:
 * {@code <image array_id>/@labels/<name>[/<level>]}. The catalog has no role column, so the path is
 * the only statement that a tensor is a set, and this class is the one place the plugin reads it. The
 * SDK's own constant for the segment is package-private, so it is owned here, as the napari widget
 * owns its copy. The rule matches the server's and the web app's: the <b>last</b> {@code @labels}
 * segment with a name after it is the one, whatever the image's own field contains; a name is
 * slash-free, so anything past it is a native pyramid level.
 */
public final class LabelId {
    /** The segment that marks a label set under its image. */
    public static final String LABELS_SEGMENT = "@labels";

    private LabelId() { }

    /** A label set's id, taken apart. */
    public static final class Address {
        /** The image this set annotates, as an array id. */
        public final String imageArrayId;
        /** The set's name, slash-free. */
        public final String name;
        /** A native pyramid level named under the set, or null. */
        public final String level;

        Address(String imageArrayId, String name, String level) {
            this.imageArrayId = imageArrayId;
            this.name = name;
            this.level = level;
        }
    }

    /** The address of {@code arrayId}, or null if it names no label set. Pass a stable id (not {@code id@token}). */
    public static Address parse(String arrayId) {
        if (arrayId == null) {
            return null;
        }
        String[] parts = arrayId.split("/", -1);
        // From 1: parts[0] is the source id, which is slash-free and so cannot be a field's @labels
        // segment. A bare source called "@labels" is a source.
        for (int i = parts.length - 2; i > 0; i--) {
            if (!parts[i].equals(LABELS_SEGMENT) || parts[i + 1].isEmpty()) {
                continue;
            }
            return new Address(String.join("/", java.util.Arrays.copyOfRange(parts, 0, i)), parts[i + 1],
                    i + 2 < parts.length ? String.join("/", java.util.Arrays.copyOfRange(parts, i + 2, parts.length))
                            : null);
        }
        return null;
    }

    /** Whether {@code arrayId} names a label set. */
    public static boolean isLabel(String arrayId) {
        return parse(arrayId) != null;
    }

    /** An image tensor and the label sets addressed under it. */
    public static final class Group {
        /** The image's catalog entry. */
        public final Map<?, ?> image;
        /** Its sets, by array id; empty for a plain tensor. */
        public final List<Map<?, ?>> labelSets = new ArrayList<>();

        Group(Map<?, ?> image) { this.image = image; }
    }

    /**
     * A source's tensors with each label set filed under the image it annotates, images in the order
     * listed. A set whose image the source does not list keeps a group of its own (its entry is the
     * "image") rather than vanishing. Mirror of the napari widget's {@code _group_tensors}.
     */
    public static List<Group> group(List<Map<?, ?>> tensors) {
        Map<String, Group> groups = new LinkedHashMap<>();
        List<Map<?, ?>> sets = new ArrayList<>();
        List<String> setImages = new ArrayList<>();
        for (Map<?, ?> t : tensors) {
            String id = String.valueOf(t.get("array_id"));
            Address a = parse(id);
            if (a != null) {
                sets.add(t);
                setImages.add(a.imageArrayId);
            } else if (!groups.containsKey(id)) {
                groups.put(id, new Group(t));
            }
        }
        for (int i = 0; i < sets.size(); i++) {
            Group g = groups.get(setImages.get(i));
            if (g != null) {
                g.labelSets.add(sets.get(i));
            } else {
                String id = String.valueOf(sets.get(i).get("array_id"));
                if (!groups.containsKey(id)) {
                    groups.put(id, new Group(sets.get(i)));
                }
            }
        }
        for (Group g : groups.values()) {
            Collections.sort(g.labelSets, (a, b) -> String.valueOf(a.get("array_id")).compareTo(String.valueOf(b.get("array_id"))));
        }
        return new ArrayList<>(groups.values());
    }
}
