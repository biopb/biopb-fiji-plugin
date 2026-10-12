package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import biopb.tensor.TensorFlightClient;
import java.util.concurrent.CountDownLatch;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.Test;

public class TensorBrowserControllerTest {
    final List<String> log = new CopyOnWriteArrayList<>();
    final List<String> opens = new CopyOnWriteArrayList<>();
    final List<String> prompts = new CopyOnWriteArrayList<>();
    volatile List<String> axesAnswers = new CopyOnWriteArrayList<>();
    volatile boolean confirmAnswer = true;
    final AtomicInteger closes = new AtomicInteger();
    RefCountedConnection conn;
    volatile RefCountedConnection active;
    final List<ImagePlus> shown = new CopyOnWriteArrayList<>();

    final TensorBrowserController.Ui ui = new TensorBrowserController.Ui() {
        @Override public void status(String t, boolean busy) { log.add("status:" + t); }
        @Override public void show(ImagePlus imp) { shown.add(imp); log.add("show:" + imp.getTitle()); }
        @Override public boolean confirm(String title, String m) { log.add("confirm"); return confirmAnswer; }
        @Override public String askAxes(String m, String prev) {
            log.add("axes"); prompts.add(m + "|" + prev);
            return axesAnswers.isEmpty() ? null : axesAnswers.remove(0);
        }
        @Override public void error(String title, String m) { log.add("error:" + title); }
    };

    @Before
    public void setUp() {
        conn = new RefCountedConnection(new TensorBrowserPanel.Connection() {
            @Override public CatalogClient catalog() { return null; }
            @Override public TensorFlightClient flight() { return null; }
            @Override public void close() { closes.incrementAndGet(); }
        }, Runnable::run);
        active = conn; // the panel's reference
    }

    /** An image whose close notification the test can fire (as ImageWindow.close() does). */
    static final class Img extends ImagePlus {
        Img(String t) { super(t, new ByteProcessor(4, 4)); }
        void fireClosed() { notifyListeners(CLOSED); }
    }

    static ImagePlus image(String id, int level) { return image(id, level, "conn-test"); }

    static ImagePlus image(String id, int level, String connId) {
        Img imp = new Img(id);
        AxisMap map = AxisMap.resolve(Arrays.asList("y", "x"), new long[] {4, 4}, null);
        imp.setProperty(TensorBrowserController.CONNECTION_PROPERTY, connId);
        imp.setProperty(TensorOpener.LevelInfo.PROPERTY, new TensorOpener.LevelInfo(id, level, new long[] {1, 1}, "", map));
        return imp;
    }

    static void await(BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timeout");
            Thread.sleep(20);
        }
        SwingUtilities.invokeAndWait(() -> { });
    }

    static final class Images implements TensorBrowserController.ImageSource {
        final List<ImagePlus> all = new ArrayList<>();
        ImagePlus current;
        @Override public ImagePlus current() { return current; }
        @Override public List<ImagePlus> all() { return all; }
        @Override public boolean isOpen(ImagePlus imp) { return all.contains(imp); }
    }

    final Images images = new Images();
    volatile TensorBrowserController.RoiFetcher fetcher = (c, id, imp, info) -> { throw new java.io.IOException("no server"); };

    TensorBrowserController controller(TensorBrowserController.Opener o) {
        return new TensorBrowserController(() -> active, ui, o, (c, id, imp, info) -> fetcher.load(c, id, imp, info), images);
    }

    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }

    void open(TensorBrowserController c, String id, int level) throws Exception { edt(() -> c.openRequested(id, level)); }

    @Test
    public void successShowsImageOffTheEdtAndImageHoldsConnection() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        open(controller((c, id, lvl, ax) -> {
            threads.add(SwingUtilities.isEventDispatchThread() ? "edt" : "worker");
            return image(id, lvl);
        }), "a", 0);
        await(() -> log.contains("show:a"));
        assertEquals(Arrays.asList("worker"), threads);
        assertEquals(2, conn.refCount());
        assertEquals(conn.id(), shown.get(0).getProperty(TensorBrowserController.CONNECTION_PROPERTY));
    }

    @Test
    public void windowClosedWithImageOpenKeepsConnectionUntilImageCloses() throws Exception {
        open(controller((c, id, lvl, ax) -> image(id, lvl)), "a", 0);
        await(() -> log.contains("show:a"));
        conn.close(); // the panel lets go (window closed)
        assertEquals(0, closes.get());
        edt(() -> ((Img) shown.get(0)).fireClosed()); // what ImageWindow.close() does
        edt(() -> { });
        await(() -> closes.get() == 1);
        assertEquals(1, closes.get());
    }

    @Test
    public void failedOpenReleasesItsReference() throws Exception {
        open(controller((c, id, lvl, ax) -> { throw new IllegalStateException("kaput"); }), "a", 0);
        await(() -> log.contains("error:Could not open a"));
        assertEquals(1, conn.refCount());
    }

    @Test
    public void tooLargeOffersCoarserLevelAndReopens() throws Exception {
        open(controller((c, id, lvl, ax) -> {
            opens.add(id + "@" + lvl);
            if (lvl == 0) throw new PlaneTooLargeException("big", 9, 1, 2);
            return image(id, lvl);
        }), "a", 0);
        await(() -> log.contains("show:a"));
        assertEquals(Arrays.asList("a@0", "a@2"), opens);
    }

    @Test
    public void declinedCoarserLevelOpensNothing() throws Exception {
        confirmAnswer = false;
        open(controller((c, id, lvl, ax) -> { opens.add("x"); throw new PlaneTooLargeException("big", 9, 1, 2); }), "a", 0);
        await(() -> log.contains("confirm"));
        assertEquals(1, opens.size());
        assertEquals(1, conn.refCount());
    }

    @Test
    public void tooLargeWithoutSuggestionIsAnError() throws Exception {
        open(controller((c, id, lvl, ax) -> { throw new PlaneTooLargeException("big", 9, 1); }), "a", 0);
        await(() -> log.contains("error:Plane too large"));
    }

    @Test
    public void axisPromptRetriesPrefilledAndStopsAfterThreeAttempts() throws Exception {
        axesAnswers = new CopyOnWriteArrayList<>(Arrays.asList("qq", "qq2", "qq3", "never"));
        open(controller((c, id, lvl, ax) -> {
            opens.add(String.valueOf(ax));
            throw new AxisMappingException(ax == null ? AxisMappingException.Reason.NEEDS_OVERRIDE
                    : AxisMappingException.Reason.INVALID_OVERRIDE, ax == null ? "need" : "bad " + ax);
        }), "a", 0);
        await(() -> log.contains("error:Could not open a"));
        assertEquals(Arrays.asList("null", "qq", "qq2", "qq3"), opens);
        assertEquals(Arrays.asList("need|", "bad qq|qq", "bad qq2|qq2"), prompts);
        assertEquals(Arrays.asList("never"), axesAnswers);
    }

    @Test
    public void axisOverrideSucceedsOnRetry() throws Exception {
        axesAnswers = new CopyOnWriteArrayList<>(Arrays.asList(" tzyx "));
        open(controller((c, id, lvl, ax) -> {
            opens.add(String.valueOf(ax));
            if (ax == null) throw new AxisMappingException(AxisMappingException.Reason.NEEDS_OVERRIDE, "need");
            return image(id, lvl);
        }), "a", 0);
        await(() -> log.contains("show:a"));
        assertEquals(Arrays.asList("null", "tzyx"), opens);
    }

    @Test
    public void cancelledAxisDialogStops() throws Exception {
        open(controller((c, id, lvl, ax) -> {
            opens.add("x");
            throw new AxisMappingException(AxisMappingException.Reason.NEEDS_OVERRIDE, "need");
        }), "a", 0);
        await(() -> log.contains("axes"));
        assertEquals(1, opens.size());
        assertEquals(1, conn.refCount());
    }

    @Test
    public void sameOpenInFlightIsNotDuplicated() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        TensorBrowserController c = controller((cl, id, lvl, ax) -> {
            opens.add("x");
            try { gate.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            return image(id, lvl);
        });
        open(c, "a", 0);
        open(c, "a", 0);
        assertTrue(log.contains("status:a is already being opened"));
        gate.countDown();
        await(() -> log.contains("show:a"));
        assertEquals(1, opens.size());
        assertEquals(2, conn.refCount());
    }

    @Test
    public void resultForADroppedConnectionIsDiscardedSilently() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        open(controller((cl, id, lvl, ax) -> {
            try { gate.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            throw new IllegalStateException("late failure");
        }), "a", 0);
        active = null; // window closed / reconnected
        conn.close();
        gate.countDown();
        await(() -> closes.get() == 1);
        assertFalse(log.toString(), log.contains("error:Could not open a"));
        assertTrue(shown.isEmpty());
    }

    @Test
    public void notConnectedIsReported() throws Exception {
        active = null;
        open(controller(null), "a", 0);
        assertTrue(log.contains("error:Tensor Browser"));
    }

    @Test
    public void displayFailureIsNotAnOpenError() throws Exception {
        TensorBrowserController.Ui bad = new TensorBrowserController.Ui() {
            @Override public void status(String t, boolean b) { log.add("status:" + t); }
            @Override public void show(ImagePlus imp) { throw new IllegalArgumentException("unlabeled axes"); }
            @Override public boolean confirm(String t, String m) { return true; }
            @Override public String askAxes(String m, String p) { log.add("axes"); return null; }
            @Override public void error(String t, String m) { log.add("error:" + t); }
        };
        TensorBrowserController c = new TensorBrowserController(() -> active, bad,
                (cl, id, lvl, ax) -> image(id, lvl), null, images);
        open(c, "a", 0);
        await(() -> log.contains("error:Could not display a"));
        assertFalse(log.contains("axes"));
        assertEquals(1, conn.refCount());
    }

    @Test
    public void roisWithoutMatchingImageTellsUser() throws Exception {
        images.all.add(image("other", 0));
        images.all.add(image("a", 0, "another-server"));
        TensorBrowserController c = controller(null);
        edt(() -> c.roisRequested("a"));
        assertTrue(log.contains("error:Load ROIs"));
        assertEquals(1, conn.refCount());
    }

    @Test
    public void pickPrefersCurrentThenFinestLevel() {
        ImagePlus a1 = image("a", 1, "c"), a0 = image("a", 0, "c"), a2 = image("a", 2, "c"), b = image("b", 0, "c");
        assertSame(a2, TensorBrowserController.pick(a2, Arrays.asList(b, a1, a0, a2), "a", "c"));
        assertSame(a0, TensorBrowserController.pick(b, Arrays.asList(b, a1, a0, a2), "a", "c"));
        assertSame(a0, TensorBrowserController.pick(null, Arrays.asList(a2, a0), "a", "c"));
        assertNull(TensorBrowserController.pick(null, Arrays.asList(b), "a", "c"));
        assertNull(TensorBrowserController.pick(null, Arrays.asList(a0), "a", "other"));
        assertNull(TensorBrowserController.pick(new ImagePlus(), Arrays.asList(new ImagePlus()), "a", "c"));
    }

    @Test
    public void roiLoadFailureIsReportedAndReleases() throws Exception {
        ImagePlus imp = image("a", 0, conn.id());
        images.all.add(imp);
        TensorBrowserController c = controller(null);
        edt(() -> c.roisRequested("a"));
        await(() -> log.contains("error:Load ROIs"));
        assertEquals(1, conn.refCount());
    }

    @Test
    public void roiTargetClosedMeansNoSuccessClaim() throws Exception {
        ImagePlus imp = image("a", 0, conn.id());
        images.all.add(imp);
        CountDownLatch gate = new CountDownLatch(1);
        fetcher = (c, id, i, info) -> {
            try { gate.await(); } catch (InterruptedException e) { throw new IOException(e); }
            return new RoiLoader.Result(new ArrayList<ij.gui.Roi>(), Collections.<String, Integer>emptyMap(), 0, false);
        };
        TensorBrowserController c = controller(null);
        edt(() -> c.roisRequested("a"));
        images.all.clear(); // the user closes the image
        gate.countDown();
        await(() -> log.stream().anyMatch(l -> l.contains("was closed")));
        assertFalse(log.stream().anyMatch(l -> l.contains("Loaded")));
    }

    @Test
    public void roiLoadReportsCountsAndMultipleMatches() throws Exception {
        ImagePlus a0 = image("a", 0, conn.id()), a1 = image("a", 1, conn.id());
        images.all.add(a1);
        images.all.add(a0);
        fetcher = (c, id, i, info) ->
                new RoiLoader.Result(new ArrayList<ij.gui.Roi>(), Collections.<String, Integer>emptyMap(), 0, true);
        TensorBrowserController c = controller(null);
        edt(() -> c.roisRequested("a"));
        await(() -> log.stream().anyMatch(l -> l.contains("Loaded 0 ROIs")));
        assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("finest level")));
        assertTrue(log.stream().anyMatch(l -> l.contains("truncated")));
    }

    @Test
    public void summaryMentionsSkippedAndTruncated() {
        RoiLoader.Result r = new RoiLoader.Result(new ArrayList<ij.gui.Roi>(),
                Collections.singletonMap("unsupported ROI shape 'mask'", 2), 2, true);
        String s = TensorBrowserController.summary(r, 0);
        assertTrue(s, s.contains("Loaded 0 ROIs") && s.contains("skipped 2") && s.contains("truncated"));
    }
}
