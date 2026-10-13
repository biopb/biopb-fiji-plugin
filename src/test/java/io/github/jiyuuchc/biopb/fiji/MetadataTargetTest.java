package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** The pane / dialog header read from a catalog row. No display needed. */
public class MetadataTargetTest {
    private static Map<String, Object> labelled(Map<String, Object> t, String... labels) {
        t.put("dim_labels", Arrays.asList(labels));
        return t;
    }

    @Test
    public void singleTensorSourceIsItsOwnTensorAndShapeIsNotSqueezed() {
        Map<String, Object> row = TreeModelBuilderTest.source("s1", "/data/a/img.zarr",
                labelled(TreeModelBuilderTest.tensor("s1/0", "uint16", 1, 3, 64, 32), "t", "c", "y", "x"));
        MetadataTarget t = MetadataTarget.of(row, null);
        assertTrue(t.hasTensor());
        assertEquals("s1", t.getSourceId());
        assertEquals("s1/0", t.getArrayId());
        assertEquals("/data/a/img.zarr", t.getPath());
        assertEquals("1×3×64×32", t.getShape());
        assertEquals("uint16", t.getDtype());
        assertEquals("t, c, y, x", t.getDims());
        assertEquals(Arrays.asList("Shape: 1×3×64×32", "Dtype: uint16", "Dims: t, c, y, x"), t.summaryLines());
    }

    @Test
    public void missingDimLabelsReadNotAvailable() {
        MetadataTarget t = MetadataTarget.of(TreeModelBuilderTest.source("s", "/x.tif",
                TreeModelBuilderTest.tensor("s/0", "uint8", 4, 4)), "s/0");
        assertEquals("N/A", t.getDims());
    }

    @Test
    public void multiTensorSourceNeedsAnArrayId() {
        Map<String, Object> row = TreeModelBuilderTest.source("s", "/x.zarr",
                TreeModelBuilderTest.tensor("s/0", "uint8", 4, 4), TreeModelBuilderTest.tensor("s/1", "float32", 2, 2));
        MetadataTarget src = MetadataTarget.of(row, null);
        assertFalse(src.hasTensor());
        assertNull(src.getArrayId());
        assertEquals("s", src.getSourceId());
        MetadataTarget one = MetadataTarget.of(row, "s/1");
        assertEquals("2×2", one.getShape());
        assertEquals("float32", one.getDtype());
        assertFalse(MetadataTarget.of(row, "nope").hasTensor());
    }

    @Test
    public void sourceWithoutUrlOrTensorsFallsBackToItsId() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_id", "abc");
        MetadataTarget t = MetadataTarget.of(row, null);
        assertEquals("abc", t.getPath());
        assertEquals("Metadata - abc", t.getTitle());
        assertFalse(t.hasTensor());
    }

    @Test
    public void aMissingOrBlankSourceIdMeansNoTarget() {
        Map<String, Object> row = new LinkedHashMap<>();
        assertNull(MetadataTarget.of(row, null));
        row.put("source_id", "  ");
        assertNull(MetadataTarget.of(row, null));
        assertNull(MetadataTarget.of(null, null));
    }

    @Test
    public void titleNamesTheSourceAndTensor() {
        Map<String, Object> row = TreeModelBuilderTest.source("s", "/d/many.zarr",
                TreeModelBuilderTest.tensor("s/a", "u1", 1), TreeModelBuilderTest.tensor("s/b", "u1", 1));
        assertEquals("Metadata - many.zarr", MetadataTarget.of(row, null).getTitle());
        assertEquals("Metadata - many.zarr / b", MetadataTarget.of(row, "s/b").getTitle());
    }
}
