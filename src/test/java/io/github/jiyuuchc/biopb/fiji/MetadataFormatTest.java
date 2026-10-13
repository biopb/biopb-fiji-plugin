package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The metadata filtering and text, matching the napari widget's rule. No display needed. */
public class MetadataFormatTest {
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void emptyMeansNullBlankAndContainersOfOnlyEmpties() {
        assertTrue(MetadataFormat.isEmptyForDisplay(null));
        assertTrue(MetadataFormat.isEmptyForDisplay(""));
        assertTrue(MetadataFormat.isEmptyForDisplay("  \t"));
        assertTrue(MetadataFormat.isEmptyForDisplay(new ArrayList<>()));
        assertTrue(MetadataFormat.isEmptyForDisplay(new LinkedHashMap<>()));
        assertTrue(MetadataFormat.isEmptyForDisplay(Arrays.asList(null, "", new ArrayList<>())));
        assertTrue(MetadataFormat.isEmptyForDisplay(map("a", null, "b", map("c", ""))));
        assertTrue(MetadataFormat.isEmptyForDisplay(new double[0]));
    }

    @Test
    public void zeroFalseAndAnythingWithContentAreNotEmpty() {
        assertFalse(MetadataFormat.isEmptyForDisplay(0));
        assertFalse(MetadataFormat.isEmptyForDisplay(0.0));
        assertFalse(MetadataFormat.isEmptyForDisplay(false));
        assertFalse(MetadataFormat.isEmptyForDisplay("x"));
        assertFalse(MetadataFormat.isEmptyForDisplay(Arrays.asList("", 0)));
        assertFalse(MetadataFormat.isEmptyForDisplay(map("a", map("b", "x"))));
        assertFalse(MetadataFormat.isEmptyForDisplay(new long[] {0}));
    }

    @Test
    public void filterDropsEmptyTopLevelEntriesKeepingOrderAndLeavesNestedOnesAlone() {
        Map<String, Object> m = map("z", 1, "gone", "", "inner", map("keep", 2, "blank", ""), "none", null,
                "list", new ArrayList<>(), "a", "x");
        Map<String, Object> f = MetadataFormat.filterEmpty(m);
        assertEquals(Arrays.asList("z", "inner", "a"), new ArrayList<>(f.keySet()));
        assertEquals(map("keep", 2, "blank", ""), f.get("inner")); // napari filters the top level only
        assertTrue(MetadataFormat.filterEmpty(null).isEmpty());
    }

    @Test
    public void textIsIndentedJsonStyle() {
        Map<String, Object> m = map("name", "a \"b\"\n", "n", 3, "f", 1.5, "ok", true,
                "scale", Arrays.asList(0.5, 0.5), "nested", map("k", "v", "deep", map("x", 1)),
                "empty", "");
        assertEquals("{\n"
                + "  \"name\": \"a \\\"b\\\"\\n\",\n"
                + "  \"n\": 3,\n"
                + "  \"f\": 1.5,\n"
                + "  \"ok\": true,\n"
                + "  \"scale\": [\n"
                + "    0.5,\n"
                + "    0.5\n"
                + "  ],\n"
                + "  \"nested\": {\n"
                + "    \"k\": \"v\",\n"
                + "    \"deep\": {\n"
                + "      \"x\": 1\n"
                + "    }\n"
                + "  }\n"
                + "}", MetadataFormat.toText(m));
    }

    @Test
    public void textHandlesArraysNestedEmptiesAndNulls() {
        Map<String, Object> m = map("dims", new long[] {1, 2}, "e", Collections.emptyList(), "n", Arrays.asList("a", null));
        m.put("only", "x");
        String t = MetadataFormat.toText(m);
        assertTrue(t, t.contains("\"dims\": [\n    1,\n    2\n  ]"));
        assertFalse(t, t.contains("\"e\""));
        assertTrue(t, t.contains("null"));
    }

    @Test
    public void nothingLeftSaysSo() {
        assertEquals(MetadataFormat.NONE, MetadataFormat.toText(null));
        assertEquals(MetadataFormat.NONE, MetadataFormat.toText(new LinkedHashMap<String, Object>()));
        assertEquals(MetadataFormat.NONE, MetadataFormat.toText(map("a", "", "b", new ArrayList<>())));
    }

    @Test
    public void controlCharactersAreEscaped() {
        assertEquals("{\n  \"k\": \"a\\u0001\\tb\"\n}", MetadataFormat.toText(map("k", "a\u0001\tb")));
        List<Object> none = new ArrayList<>();
        assertEquals(MetadataFormat.NONE, MetadataFormat.toText(map("k", none)));
    }

    @Test
    public void hugeTextStopsAtTheCapAtALineEndWithANote() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 20000; i++) {
            m.put("key" + i, "value value value " + i);
        }
        String t = MetadataFormat.toText(m);
        assertTrue(t.length() < MetadataFormat.MAX_CHARS + 50);
        assertTrue(t, t.endsWith("\n… more lines not shown"));
        String small = MetadataFormat.toText(m, 100);
        assertTrue(small.length() < 150);
        assertTrue(small, small.startsWith("{\n  \"key0\": \"value value value 0\",\n"));
    }

    @Test
    public void deepNestingIsCutWithAnEllipsisNotAStackOverflow() {
        Object v = "leaf";
        for (int i = 0; i < 100000; i++) {
            v = Collections.singletonMap("k", v);
        }
        // isEmptyForDisplay recurses too, so build the map below that depth: the writer is what is limited here
        Object deep = "leaf";
        for (int i = 0; i < 200; i++) {
            deep = Collections.singletonMap("k", deep);
        }
        @SuppressWarnings("unchecked")
        String t = MetadataFormat.toText((Map<String, Object>) deep);
        assertTrue(t, t.contains("…"));
        assertFalse(t, t.contains("leaf"));
    }
}
