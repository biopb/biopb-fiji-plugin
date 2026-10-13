package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import org.junit.Test;

/** Which top-level rows can be removed. */
public class DroppedSourcesTest {

    @Test
    public void rootIsTheFirstSegment() {
        assertEquals("dnd://scans", DroppedSources.rootOf("dnd://scans/a/b.zarr"));
        assertEquals("dnd://scans", DroppedSources.rootOf("dnd://scans\\a\\b"));
        assertEquals("dnd://x.tif", DroppedSources.rootOf("dnd://x.tif"));
        assertNull(DroppedSources.rootOf("/data/scans"));
        assertNull(DroppedSources.rootOf("dnd://"));
        assertNull(DroppedSources.rootOf(null));
    }

    @Test
    public void rootsOfDescriptors() {
        Map<String, Object> a = Collections.<String, Object>singletonMap("source_url", "dnd://one/x");
        Map<String, Object> b = Collections.<String, Object>singletonMap("source_url", "/plain/two");
        Map<String, Object> c = Collections.<String, Object>singletonMap("source_url", "dnd://one/y");
        assertEquals(Collections.singleton("dnd://one"), DroppedSources.rootsOf(Arrays.asList(a, b, c)));
    }

    @Test
    public void folderIsMatchedByNameAgainstTheDroppedSet() {
        java.util.Set<String> roots = new HashSet<>(Collections.singleton("dnd://scans"));
        assertEquals("dnd://scans", DroppedSources.removeRootForFolder("scans", roots));
        assertEquals("dnd://scans", DroppedSources.removeRootForFolder("scans/sub", roots));
        assertNull(DroppedSources.removeRootForFolder("other", roots)); // a plain folder of another name
        assertNull(DroppedSources.removeRootForFolder("scans", Collections.<String>emptySet()));
    }

    @Test
    public void aFolderWhoseNameOnlyStartsLikeARootIsNotThatRoot() {
        java.util.Set<String> roots = new HashSet<>(Collections.singleton("dnd://scans2"));
        assertNull(DroppedSources.removeRootForFolder("scans", roots)); // same prefix, other root
        assertNull(DroppedSources.removeRootForFolder("Scans", roots));
        assertEquals("dnd://scans2", DroppedSources.removeRootForFolder("scans2", roots));
    }

    @Test
    public void aSourceIsRemovableOnlyWhenItIsTheRoot() {
        assertEquals("dnd://x.tif", DroppedSources.removeRootForSource("dnd://x.tif"));
        assertNull(DroppedSources.removeRootForSource("dnd://dir/x.tif"));
        assertNull(DroppedSources.removeRootForSource("/data/x.tif"));
    }
}
