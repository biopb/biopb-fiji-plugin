package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Adding and removing sources: {@link SourceList} against a fake server, and {@link SourceEditor}'s flow. */
public class SourceEditorTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** A server with an empty catalog whose add and remove are scripted. */
    static class Fake implements CatalogClient {
        final AtomicInteger listings = new AtomicInteger();
        final List<String> registered = new CopyOnWriteArrayList<>();
        final List<Boolean> cloudFlags = new CopyOnWriteArrayList<>();
        final List<String> deregistered = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(0);
        volatile SourceChanges.AddResult addAnswer = new SourceChanges.AddResult(Arrays.asList("s1", "s2"),
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<SourceChanges.Failure>emptyList(), 0, 0);
        volatile SourceChanges.RemoveResult removeAnswer = new SourceChanges.RemoveResult(Arrays.asList("s1"),
                Collections.<SourceChanges.Failure>emptyList());
        volatile IOException addFails;
        volatile boolean sawCancel;
        volatile boolean relistFails;
        volatile List<Map<String, Object>> droppedRows = Collections.emptyList();
        volatile Runnable duringAdd;

        @Override public Level catalogChildren(String key, int limit) throws IOException {
            if (listings.incrementAndGet() > 1 && relistFails) {
                throw new IOException("relist down");
            }
            return new Level("", Collections.<Folder>emptyList(), Collections.<Source>emptyList(), false);
        }

        @Override public List<Map<String, Object>> query(String sql) {
            return sql.contains("dnd://") ? droppedRows : new ArrayList<Map<String, Object>>();
        }

        @Override public Map<String, Object> healthCheck() { return new LinkedHashMap<>(); }
        @Override public String sourceRowColumns() { return null; }

        @Override public SourceChanges.AddResult registerLocalPath(String path, boolean cloud,
                Consumer<SourceChanges.AddProgress> progress, BooleanSupplier cancel) throws IOException {
            registered.add(path);
            cloudFlags.add(cloud);
            if (progress != null) {
                progress.accept(new SourceChanges.AddProgress(1, path));
            }
            started.countDown();
            try {
                while (!release.await(5, TimeUnit.MILLISECONDS)) {
                    if (cancel != null && cancel.getAsBoolean()) {
                        sawCancel = true;
                        return new SourceChanges.AddResult(null, null, null, null, 0, 0);
                    }
                }
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            if (duringAdd != null) {
                duringAdd.run();
            }
            if (addFails != null) {
                throw addFails;
            }
            return addAnswer;
        }

        @Override public SourceChanges.RemoveResult deregisterLocalPath(String rootUrl) throws IOException {
            deregistered.add(rootUrl);
            return removeAnswer;
        }
    }

    /** Records what the editor asks and shows; answers questions from {@code yes}. */
    static class FakePrompts implements SourceEditor.Prompts {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final List<String> infos = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile boolean yes = true;

        @Override public boolean confirm(String title, String message, boolean defaultYes) {
            asked.add(title);
            return yes;
        }

        @Override public void info(String title, String message) { infos.add(title + "|" + message); }
        @Override public void error(String title, String message) { errors.add(title + "|" + message); }
    }

    private Fake fake;
    private FakePrompts prompts;
    private SourceList list;
    private SourceEditor editor;
    private final List<String> statuses = new CopyOnWriteArrayList<>();
    private final AtomicInteger changes = new AtomicInteger();
    private volatile SourceList current;
    private volatile String url = "grpc://localhost:8815";
    private volatile boolean connecting;

    @Before
    public void setUp() throws Exception {
        fake = new Fake();
        list = new SourceList(fake);
        list.refresh();
        current = list;
        prompts = new FakePrompts();
        edt(() -> {
            editor = new SourceEditor(new SourceEditor.Host() {
                @Override public SourceList list() { return current; }
                @Override public String url() { return url; }
                @Override public boolean connecting() { return connecting; }
                @Override public Component parent() { return null; }
                @Override public void status(String text) { statuses.add(text); }
                @Override public void catalogChanged() { changes.incrementAndGet(); }
            });
            editor.setPrompts(prompts);
        });
    }

    private static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }

    private static void until(java.util.function.BooleanSupplier c) throws Exception {
        for (int i = 0; i < 1000 && !c.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue("timed out", c.getAsBoolean());
    }

    private boolean idle() throws Exception {
        boolean[] r = new boolean[1];
        edt(() -> r[0] = !editor.isBusy());
        return r[0];
    }

    // --- SourceList ----------------------------------------------------------

    @Test
    public void listAddRelistsAndReturnsTheResult() throws Exception {
        int before = fake.listings.get();
        SourceChanges.AddResult r = list.add("/data/x", true, null, null);
        assertEquals(Arrays.asList("s1", "s2"), r.getAdded());
        assertEquals(Arrays.asList("/data/x"), fake.registered);
        assertEquals(Arrays.asList(true), fake.cloudFlags);
        assertTrue(fake.listings.get() > before);
    }

    @Test
    public void listAddSurvivesAFailingRelist() throws Exception {
        fake.relistFails = true;
        assertEquals(2, list.add("/data/x", false, null, null).getAdded().size()); // the add happened
        assertEquals(1, list.remove("dnd://x").getRemoved().size());
    }

    @Test
    public void listAddReturnsTheResultEvenIfTheConnectionWasReplacedMeanwhile() throws Exception {
        CatalogClient other = new Fake();
        CatalogClient[] now = {fake};
        SourceList l = new SourceList(() -> now[0]);
        l.refresh();
        fake.duringAdd = () -> now[0] = other;
        int listings = fake.listings.get();
        assertEquals(2, l.add("/data/x", false, null, null).getAdded().size()); // the server did add them
        assertEquals(listings, fake.listings.get()); // but no re-list into the other connection
    }

    private int rootQueries;

    private SourceList countingList() throws Exception {
        Fake f = new Fake() {
            @Override public List<Map<String, Object>> query(String sql) {
                if (sql.contains("regexp_extract")) {
                    rootQueries++;
                }
                return super.query(sql);
            }
        };
        SourceList l = new SourceList(f);
        l.refresh();
        return l;
    }

    @Test
    public void droppedRootsAreReadOnceNotOnEveryRelist() throws Exception {
        SourceList l = countingList();
        assertEquals(1, rootQueries);
        l.refresh();
        l.refresh();
        assertEquals(1, rootQueries);
        l.add("/data/x", false, null, null); // an add changes what is dropped
        assertEquals(2, rootQueries);
        l.remove("dnd://x");
        assertEquals(3, rootQueries);
        l.clear(); // a new connection
        l.refresh();
        assertEquals(4, rootQueries);
    }

    @Test
    public void aCatalogChangeTheWatcherSeesReadsTheRootsAgain() throws Exception {
        Fake f = new Fake() {
            @Override public List<Map<String, Object>> query(String sql) {
                if (sql.contains("regexp_extract")) {
                    rootQueries++;
                }
                return super.query(sql);
            }

            @Override public Map<String, Object> healthCheck() {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("source_count", (long) listings.get() > 3 ? 5L : 1L);
                return h;
            }
        };
        SourceList l = new SourceList(f);
        l.refresh();
        l.poll(); // the baseline
        assertEquals(1, rootQueries);
        f.listings.set(10); // the count moves
        l.poll();
        assertEquals(2, rootQueries);
    }

    @Test
    public void theFullListingFallbackRecomputesTheRootsFromTheRows() throws Exception {
        Fake old = new Fake() {
            @Override public Level catalogChildren(String key, int limit) {
                throw new UnsupportedOperationException("old server");
            }

            @Override public List<Map<String, Object>> query(String sql) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source_id", "a");
                row.put("source_url", "dnd://scans/a.zarr");
                return new ArrayList<>(Arrays.asList(row));
            }
        };
        SourceList l = new SourceList(old);
        l.refresh();
        assertEquals(Collections.singleton("dnd://scans"), l.getDroppedRoots());
    }

    @Test
    public void searchResultsAddTheirRoots() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_id", "z");
        row.put("source_url", "dnd://found/z");
        list.addSources(Collections.singletonMap("z", row));
        assertEquals(Collections.singleton("dnd://found"), list.getDroppedRoots());
    }

    @Test
    public void aMarkMadeWhileTheRootsAreBeingReadSurvivesTheRead() throws Exception {
        SourceList[] self = new SourceList[1];
        boolean[] marked = {false};
        Fake f = new Fake() {
            @Override public List<Map<String, Object>> query(String sql) {
                if (sql.contains("regexp_extract")) {
                    rootQueries++;
                    if (!marked[0]) {
                        marked[0] = true;
                        self[0].markRootsStale(); // something changed while this read is out
                    }
                }
                return super.query(sql);
            }
        };
        self[0] = new SourceList(f);
        self[0].refresh();
        assertEquals(1, rootQueries);
        self[0].refresh(); // the read above is older than the mark: read again
        assertEquals(2, rootQueries);
        self[0].refresh(); // and now it is current
        assertEquals(2, rootQueries);
    }

    @Test
    public void listReadsDroppedRoots() throws Exception {
        fake.droppedRows = Arrays.asList(Collections.<String, Object>singletonMap("root", "dnd://scans"),
                Collections.<String, Object>singletonMap("root", ""));
        list.clear(); // a new connection reads them
        list.refresh();
        assertEquals(Collections.singleton("dnd://scans"), list.getDroppedRoots());
        list.clear();
        assertTrue(list.getDroppedRoots().isEmpty());
    }

    // --- the gate -------------------------------------------------------------

    @Test
    public void aRemoteServerRefusesAndShowsWhy() throws Exception {
        url = "grpc://example.com:8815";
        edt(() -> {
            editor.updateHint();
            assertEquals(DropPolicy.REMOTE, editor.hintText());
            assertFalse(editor.addButtonForTest().isEnabled());
            editor.drop("/data/x");
        });
        assertTrue(fake.registered.isEmpty());
    }

    @Test
    public void notConnectedOrConnectingRefuses() throws Exception {
        connecting = true;
        edt(() -> {
            editor.updateHint();
            assertEquals(DropPolicy.NOT_CONNECTED, editor.hintText());
            editor.drop("/data/x");
        });
        connecting = false;
        current = null;
        edt(() -> {
            editor.updateHint();
            assertEquals(DropPolicy.NOT_CONNECTED, editor.hintText());
            editor.drop("/data/x");
        });
        assertTrue(fake.registered.isEmpty());
    }

    @Test
    public void aLocalServerInvitesADrop() throws Exception {
        edt(() -> {
            assertEquals(DropPolicy.READY, editor.hintText());
            assertTrue(editor.addButtonForTest().isEnabled());
        });
    }

    // --- adding ---------------------------------------------------------------

    @Test
    public void addReportsAndRefreshes() throws Exception {
        edt(() -> editor.drop("/data/x"));
        until(this::idleQuiet);
        assertEquals(Arrays.asList("/data/x"), fake.registered);
        assertEquals(Arrays.asList(false), fake.cloudFlags);
        assertEquals(1, changes.get());
        assertTrue(statuses.contains("Add data: added 2"));
        assertTrue(prompts.errors.isEmpty());
        assertTrue(prompts.asked.isEmpty());
        edt(() -> assertEquals(DropPolicy.READY, editor.hintText()));
    }

    private boolean idleQuiet() {
        try {
            return idle();
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void progressShowsInTheHintAndASecondAddWaits() throws Exception {
        fake.release = new CountDownLatch(1);
        edt(() -> editor.drop("/data/x"));
        assertTrue(fake.started.await(5, TimeUnit.SECONDS));
        until(() -> {
            String[] h = new String[1];
            try { edt(() -> h[0] = editor.hintText()); } catch (Exception e) { return false; }
            return h[0].startsWith("Adding… 1 source registered");
        });
        edt(() -> {
            assertTrue(editor.cancelButtonForTest().isVisible());
            assertFalse(editor.addButtonForTest().isEnabled());
            editor.drop("/data/y"); // one add at a time
        });
        assertEquals(1, fake.registered.size());
        fake.release.countDown();
        until(this::idleQuiet);
    }

    @Test
    public void cancelStopsTheWalk() throws Exception {
        fake.release = new CountDownLatch(1);
        edt(() -> editor.drop("/data/x"));
        assertTrue(fake.started.await(5, TimeUnit.SECONDS));
        edt(() -> {
            editor.cancelAdd();
            assertEquals("Cancelling…", editor.hintText());
        });
        until(this::idleQuiet);
        assertTrue(fake.sawCancel);
        assertTrue(statuses.contains("Add data: nothing"));
        edt(() -> assertFalse(editor.cancelButtonForTest().isVisible()));
    }

    @Test
    public void failuresGetADialogAndWholeFailuresToo() throws Exception {
        fake.addAnswer = new SourceChanges.AddResult(Arrays.asList("s1"), null, null,
                Arrays.asList(new SourceChanges.Failure("/data/x/bad", "unreadable")), 0, 0);
        edt(() -> editor.drop("/data/x"));
        until(this::idleQuiet);
        assertEquals(Arrays.asList("Some items were not added|• bad: unreadable"), prompts.errors);
        assertTrue(statuses.contains("Add data: added 1, 1 failed"));

        prompts.errors.clear();
        fake.addFails = new IOException("path not found");
        edt(() -> editor.drop("/data/y"));
        until(this::idleQuiet);
        assertEquals(Arrays.asList("Add data failed|path not found"), prompts.errors);
        assertTrue(statuses.contains("Add data failed: path not found"));
        edt(() -> assertEquals(DropPolicy.READY, editor.hintText()));
    }

    @Test
    public void aLargeFolderAsksFirst() throws Exception {
        File dir = tmp.newFolder("big");
        for (int i = 0; i < CloudDrop.LARGE_ENTRY_THRESHOLD + 1; i++) {
            assertTrue(new File(dir, "f" + i).createNewFile());
        }
        prompts.yes = false;
        edt(() -> editor.drop(dir.getPath()));
        until(this::idleQuiet);
        assertEquals(Arrays.asList("Add large folder?"), prompts.asked);
        assertTrue(fake.registered.isEmpty());
        prompts.yes = true;
        edt(() -> editor.drop(dir.getPath()));
        until(this::idleQuiet);
        assertEquals(1, fake.registered.size());
    }

    @Test
    public void aSecondDropOrAddCannotStartUnderTheFirstOnesQuestion() throws Exception {
        File dir = tmp.newFolder("big2");
        edt(() -> editor.setSizer(p -> true));
        List<Boolean> addEnabledInPrompt = new CopyOnWriteArrayList<>();
        SourceEditor.Prompts nested = new SourceEditor.Prompts() {
            @Override public boolean confirm(String title, String message, boolean defaultYes) {
                addEnabledInPrompt.add(editor.addButtonForTest().isEnabled());
                editor.drop("/data/other"); // dropped while the question is up
                editor.setChooser(() -> new File("/data/picked"));
                editor.addButtonForTest().doClick();
                assertTrue(editor.isBusy());
                return true;
            }

            @Override public void info(String t, String m) { }
            @Override public void error(String t, String m) { }
        };
        edt(() -> editor.setPrompts(nested));
        edt(() -> editor.drop(dir.getPath()));
        until(this::idleQuiet);
        assertEquals(Arrays.asList(false), addEnabledInPrompt);
        assertEquals(Arrays.asList(dir.getPath()), fake.registered); // the confirmed add was not abandoned
    }

    @Test
    public void ifTheSizeCannotBeReadTheUserIsAsked() throws Exception {
        edt(() -> editor.setSizer(p -> { throw new IllegalStateException("walk failed"); }));
        edt(() -> editor.drop("/data/x"));
        until(this::idleQuiet);
        assertEquals(Arrays.asList("Add large folder?"), prompts.asked);
    }

    @Test
    public void canChangeFollowsTheGate() throws Exception {
        edt(() -> assertTrue(editor.canChange()));
        fake.release = new CountDownLatch(1);
        edt(() -> editor.drop("/data/x"));
        assertTrue(fake.started.await(5, TimeUnit.SECONDS));
        edt(() -> assertFalse(editor.canChange()));
        fake.release.countDown();
        until(this::idleQuiet);
        url = "grpc://example.com:1";
        edt(() -> assertFalse(editor.canChange()));
    }

    @Test
    public void theConfirmationNamesTheRootBeingDeregistered() throws Exception {
        final String[] text = new String[1];
        edt(() -> editor.setPrompts(new FakePrompts() {
            @Override public boolean confirm(String title, String message, boolean defaultYes) {
                text[0] = message;
                return false;
            }
        }));
        edt(() -> editor.remove("dnd://scans", "scans"));
        assertTrue(text[0], text[0].contains("dnd://scans"));
        assertTrue(text[0].contains("same name"));
    }

    @Test
    public void theHandlerKeepsTheComponentsOwnCopy() throws Exception {
        javax.swing.JTree tree = new javax.swing.JTree();
        edt(() -> {
            tree.setSelectionRow(1);
            editor.acceptDropsOn(tree);
            java.awt.datatransfer.Clipboard clip = new java.awt.datatransfer.Clipboard("test");
            javax.swing.TransferHandler h = tree.getTransferHandler();
            assertTrue((h.getSourceActions(tree) & javax.swing.TransferHandler.COPY) != 0);
            h.exportToClipboard(tree, clip, javax.swing.TransferHandler.COPY);
            assertNotNull(clip.getContents(null));
            assertTrue(clip.getContents(null).isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor));
        });
    }

    @Test
    public void aSmallFolderDoesNotAsk() throws Exception {
        File dir = tmp.newFolder("small");
        edt(() -> editor.drop(dir.getPath()));
        until(this::idleQuiet);
        assertTrue(prompts.asked.isEmpty());
    }

    @Test
    public void aOneDriveFolderWarnsAndAddsAsCloudWhenConfirmed() throws Exception {
        prompts.yes = false;
        edt(() -> editor.drop("/home/me/OneDrive/scans"));
        until(this::idleQuiet);
        assertEquals(Arrays.asList("Add source from a cloud folder?"), prompts.asked);
        assertTrue(fake.registered.isEmpty());
        prompts.yes = true;
        edt(() -> editor.drop("/home/me/OneDrive/scans"));
        until(this::idleQuiet);
        assertEquals(Arrays.asList(true), fake.cloudFlags);
    }

    @Test
    public void whatWasLeftOutIsToldAndAskedAboutOnTheNextAdd() throws Exception {
        fake.addAnswer = new SourceChanges.AddResult(Arrays.asList("s1"), null, null, null, 2, 0);
        edt(() -> editor.drop("/data/scans"));
        until(this::idleQuiet);
        assertEquals(1, prompts.infos.size());
        assertTrue(prompts.infos.get(0).startsWith("Some files were not added|2 offline files"));
        assertTrue(prompts.asked.isEmpty());

        edt(() -> editor.drop("/data/scans")); // again: asks about cloud mode
        until(this::idleQuiet);
        assertEquals(Arrays.asList("Add as cloud sources?"), prompts.asked);
        assertEquals(Arrays.asList(false, true), fake.cloudFlags);
        prompts.asked.clear();
        fake.addAnswer = new SourceChanges.AddResult(Arrays.asList("s1"), null, null, null, 0, 0);
        edt(() -> editor.drop("/data/scans")); // the cloud add went through: no longer remembered
        until(this::idleQuiet);
        assertTrue(prompts.asked.isEmpty());
    }

    @Test
    public void anOutcomeAfterTheConnectionChangedIsDropped() throws Exception {
        fake.release = new CountDownLatch(1);
        edt(() -> editor.drop("/data/x"));
        assertTrue(fake.started.await(5, TimeUnit.SECONDS));
        edt(() -> editor.connectionChanged());
        fake.release.countDown();
        Thread.sleep(200);
        edt(() -> { });
        assertEquals(0, changes.get());
        assertTrue(statuses.isEmpty());
        assertTrue(prompts.errors.isEmpty());
        edt(() -> assertFalse(editor.isAdding()));
    }

    @Test
    public void thePickerAddsWhatItReturns() throws Exception {
        File f = tmp.newFile("x.tif");
        editor.setChooser(() -> f);
        edt(() -> editor.addButtonForTest().doClick());
        until(this::idleQuiet);
        assertEquals(Arrays.asList(f.getPath()), fake.registered);
        editor.setChooser(() -> null);
        edt(() -> editor.addButtonForTest().doClick());
        until(this::idleQuiet);
        assertEquals(1, fake.registered.size());
    }

    // --- removing -------------------------------------------------------------

    @Test
    public void removeAsksThenDeregistersAndRefreshes() throws Exception {
        prompts.yes = false;
        edt(() -> editor.remove("dnd://scans", "scans"));
        assertTrue(fake.deregistered.isEmpty());
        prompts.yes = true;
        edt(() -> editor.remove("dnd://scans", "scans"));
        until(this::idleQuiet);
        assertEquals(Arrays.asList("dnd://scans"), fake.deregistered);
        assertEquals(1, changes.get());
        assertEquals("Removed 1 source", statuses.get(statuses.size() - 1));
        assertEquals(Arrays.asList("Remove dropped source?", "Remove dropped source?"), prompts.asked);
    }

    @Test
    public void removeIsRefusedWhileAnAddRunsAndTheOtherWayRound() throws Exception {
        fake.release = new CountDownLatch(1);
        edt(() -> editor.drop("/data/x"));
        assertTrue(fake.started.await(5, TimeUnit.SECONDS));
        edt(() -> editor.remove("dnd://scans", "scans"));
        assertTrue(fake.deregistered.isEmpty());
        assertTrue(prompts.asked.isEmpty()); // not even asked
        assertEquals("Cannot remove now: " + DropPolicy.ADDING, statuses.get(statuses.size() - 1));
        fake.release.countDown();
        until(this::idleQuiet);

        // a removal running holds off an add
        CountDownLatch gate = new CountDownLatch(1);
        Fake slow = new Fake() {
            @Override public SourceChanges.RemoveResult deregisterLocalPath(String r) throws IOException {
                try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
                return removeAnswer;
            }
        };
        current = new SourceList(slow);
        current.refresh();
        edt(() -> editor.remove("dnd://scans", "scans"));
        edt(() -> {
            assertEquals(SourceEditor.REMOVING, editor.hintText());
            assertFalse(editor.addButtonForTest().isEnabled());
            editor.drop("/data/y");
        });
        assertTrue(slow.registered.isEmpty());
        gate.countDown();
        until(this::idleQuiet);
        edt(() -> assertEquals(DropPolicy.READY, editor.hintText()));
    }

    @Test
    public void removeIsRefusedAgainstARemoteServer() throws Exception {
        url = "grpc://example.com:8815";
        edt(() -> editor.remove("dnd://scans", "scans"));
        assertTrue(fake.deregistered.isEmpty());
        assertEquals("Cannot remove now: " + DropPolicy.REMOTE, statuses.get(statuses.size() - 1));
    }

    @Test
    public void aClipboardPasteIsNotAnAdd() throws Exception {
        javax.swing.JTree tree = new javax.swing.JTree();
        edt(() -> editor.acceptDropsOn(tree));
        java.awt.datatransfer.Transferable files = new java.awt.datatransfer.Transferable() {
            @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[] {java.awt.datatransfer.DataFlavor.javaFileListFlavor};
            }

            @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) {
                return f.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @Override public Object getTransferData(java.awt.datatransfer.DataFlavor f) {
                return Arrays.asList(new File("/data/x"));
            }
        };
        javax.swing.TransferHandler.TransferSupport paste = new javax.swing.TransferHandler.TransferSupport(tree, files);
        assertFalse(paste.isDrop());
        edt(() -> {
            assertFalse(tree.getTransferHandler().canImport(paste));
            assertFalse(tree.getTransferHandler().importData(paste));
        });
        edt(() -> { });
        assertTrue(fake.registered.isEmpty());
    }

    @Test
    public void removeFailuresAreReported() throws Exception {
        fake.removeAnswer = new SourceChanges.RemoveResult(null,
                Arrays.asList(new SourceChanges.Failure("s9", "busy")));
        edt(() -> editor.remove("dnd://scans", "scans"));
        until(this::idleQuiet);
        assertEquals(0, changes.get());
        assertEquals("Nothing to remove", statuses.get(statuses.size() - 1));
        assertEquals(Arrays.asList("Some sources were not removed|• s9: busy"), prompts.errors);
    }

    @Test
    public void aWholeRemoveFailureIsReported() throws Exception {
        CatalogClient refusing = new Fake() {
            @Override public SourceChanges.RemoveResult deregisterLocalPath(String r) throws IOException {
                throw new IOException("not a dropped root");
            }
        };
        current = new SourceList(refusing);
        current.refresh();
        edt(() -> editor.remove("dnd://scans", "scans"));
        until(this::idleQuiet);
        assertEquals(Arrays.asList("Remove failed|not a dropped root"), prompts.errors);
    }

    @Test
    public void aRemoveOutcomeAfterTheConnectionChangedIsDropped() throws Exception {
        // Held in the server until released: the editor forgets it first.
        CountDownLatch gate = new CountDownLatch(1);
        Fake slow = new Fake() {
            @Override public SourceChanges.RemoveResult deregisterLocalPath(String r) throws IOException {
                try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
                return removeAnswer;
            }
        };
        current = new SourceList(slow);
        current.refresh();
        edt(() -> editor.remove("dnd://scans", "scans"));
        edt(() -> editor.connectionChanged());
        gate.countDown();
        Thread.sleep(200);
        edt(() -> { });
        assertEquals(0, changes.get());
        assertNotNull(statuses);
        assertEquals(Arrays.asList("Removing scans…"), statuses);
    }

    @Test
    public void defaultClientRefusesWhatItCannotDo() {
        CatalogClient bare = new CatalogClient() {
            @Override public Level catalogChildren(String k, int l) { return null; }
            @Override public List<Map<String, Object>> query(String s) { return null; }
            @Override public Map<String, Object> healthCheck() { return null; }
            @Override public String sourceRowColumns() { return null; }
        };
        try {
            bare.registerLocalPath("/x", false, null, null);
            fail();
        } catch (UnsupportedOperationException | IOException expected) {
            // fine
        }
        try {
            bare.deregisterLocalPath("dnd://x");
            fail();
        } catch (UnsupportedOperationException | IOException expected) {
            // fine
        }
    }
}
