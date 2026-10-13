package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Cloud-name detection and the large-folder threshold. */
public class CloudDropTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void oneDriveNames() {
        assertTrue(CloudDrop.isOneDriveDirName("OneDrive"));
        assertTrue(CloudDrop.isOneDriveDirName("onedrive"));
        assertTrue(CloudDrop.isOneDriveDirName("OneDrive - Contoso"));
        assertTrue(CloudDrop.isOneDriveDirName("OneDrive-Personal"));
        assertFalse(CloudDrop.isOneDriveDirName("OneDrives"));
        assertFalse(CloudDrop.isOneDriveDirName("MyOneDrive"));
        assertFalse(CloudDrop.isOneDriveDirName("OneDriveBackup"));
    }

    @Test
    public void warningForAPathInsideOneDrive() {
        String w = CloudDrop.warning("C:\\Users\\me\\OneDrive - Org\\scans\\slide.svs");
        assertNotNull(w);
        assertTrue(w.contains("\u201cslide.svs\u201d is inside OneDrive"));
        assertTrue(w.endsWith("Add anyway?"));
        assertNotNull(CloudDrop.warning("/home/me/OneDrive/x/"));
    }

    @Test
    public void noWarningElsewhere() {
        assertNull(CloudDrop.warning("/data/scans/slide.svs"));
        assertNull(CloudDrop.warning("/data/OneDrives/slide.svs"));
        assertNull(CloudDrop.warning(""));
    }

    @Test
    public void displayNameIgnoresTrailingSeparators() {
        assertEquals("c", CloudDrop.displayName("/a/b/c/"));
        assertEquals("c", CloudDrop.displayName("C:\\a\\b\\c\\"));
        assertEquals("/", CloudDrop.displayName("/"));
    }

    private void files(File dir, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            assertTrue(new File(dir, "f" + i).createNewFile());
        }
    }

    @Test
    public void thresholdCountsFilesAndFoldersRecursively() throws Exception {
        File root = tmp.newFolder("root");
        files(root, 3);
        File sub = new File(root, "sub");
        assertTrue(sub.mkdir());
        files(sub, 2); // 3 + 1 folder + 2 = 6 entries
        assertFalse(CloudDrop.exceedsEntryThreshold(root.getPath(), 6));
        assertTrue(CloudDrop.exceedsEntryThreshold(root.getPath(), 5));
    }

    @Test
    public void aFileOrMissingPathIsNeverLarge() throws Exception {
        File f = tmp.newFile("one");
        assertFalse(CloudDrop.exceedsEntryThreshold(f.getPath(), 0));
        assertFalse(CloudDrop.exceedsEntryThreshold(new File(tmp.getRoot(), "nope").getPath(), 0));
    }

    @Test
    public void symlinkedFoldersAreNotFollowed() throws Exception {
        File big = tmp.newFolder("big");
        files(big, 10);
        File root = tmp.newFolder("root");
        try {
            Files.createSymbolicLink(new File(root, "link").toPath(), big.toPath());
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // no symlinks on this system
        }
        assertFalse(CloudDrop.exceedsEntryThreshold(root.getPath(), 1));
    }

    @Test
    public void aDroppedLinkToAFolderIsSizedByWhatItPointsAt() throws Exception {
        File big = tmp.newFolder("target");
        files(big, 10);
        File link = new File(tmp.getRoot(), "link");
        try {
            Files.createSymbolicLink(link.toPath(), big.toPath());
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // no symlinks on this system
        }
        assertTrue(CloudDrop.exceedsEntryThreshold(link.getPath(), 5));
        assertFalse(CloudDrop.exceedsEntryThreshold(link.getPath(), 10));
    }

    @Test
    public void aLinkCycleBelowTheRootDoesNotLoop() throws Exception {
        File root = tmp.newFolder("cyc");
        files(root, 2);
        try {
            Files.createSymbolicLink(new File(root, "up").toPath(), root.toPath());
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }
        assertFalse(CloudDrop.exceedsEntryThreshold(root.getPath(), 3)); // 2 files + the link
        assertTrue(CloudDrop.exceedsEntryThreshold(root.getPath(), 2));
    }

    @Test
    public void defaultThresholdIsNapari() throws Exception {
        assertEquals(2000, CloudDrop.LARGE_ENTRY_THRESHOLD);
        File root = tmp.newFolder("small");
        files(root, 5);
        assertFalse(CloudDrop.exceedsEntryThreshold(root.getPath()));
    }
}
