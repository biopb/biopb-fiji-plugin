package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import biopb.tensor.TensorFlightClient;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TensorBrowserControllerTest {
    final List<String> log = new CopyOnWriteArrayList<>();
    final List<String> opens = new CopyOnWriteArrayList<>();
    volatile String axesAnswer;
    volatile boolean confirmAnswer = true;
    TensorFlightClient client;

    final TensorBrowserController.Ui ui = new TensorBrowserController.Ui() {
        @Override public void status(String t, boolean busy) { log.add("status:" + t); }
        @Override public void show(ImagePlus imp) { log.add("show:" + imp.getTitle()); }
        @Override public boolean confirm(String title, String m) { log.add("confirm"); return confirmAnswer; }
        @Override public String askAxes(String m) { log.add("axes"); return axesAnswer; }
        @Override public void error(String title, String m) { log.add("error:" + title); }
    };

    @Before
    public void setUp() { client = new TensorFlightClient("localhost", 1); } // lazy: never contacted

    @After
    public void tearDown() { client.close(); }

    static ImagePlus image(String id, int level) {
        ImagePlus imp = new ImagePlus(id, new ByteProcessor(4, 4));
        AxisMap map = AxisMap.resolve(Arrays.asList("y", "x"), new long[] {4, 4}, null);
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

    TensorBrowserController controller(TensorBrowserController.Opener o, List<ImagePlus> imgs) {
        return new TensorBrowserController(() -> client, ui, o,
                (c, id, imp, info) -> { throw new java.io.IOException("no server"); }, () -> imgs);
    }

    @Test
    public void successShowsImageOffTheEdt() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        controller((c, id, lvl, ax) -> {
            threads.add(SwingUtilities.isEventDispatchThread() ? "edt" : "worker");
            return image(id, lvl);
        }, Collections.<ImagePlus>emptyList()).openRequested("a", 0);
        await(() -> log.contains("show:a"));
        assertEquals(Arrays.asList("worker"), threads);
    }

    @Test
    public void tooLargeOffersCoarserLevelAndReopens() throws Exception {
        controller((c, id, lvl, ax) -> {
            opens.add(id + "@" + lvl);
            if (lvl == 0) throw new PlaneTooLargeException("big; try pyramid level 2", 9, 1);
            return image(id, lvl);
        }, Collections.<ImagePlus>emptyList()).openRequested("a", 0);
        await(() -> log.contains("show:a"));
        assertEquals(Arrays.asList("a@0", "a@2"), opens);
        assertTrue(log.contains("confirm"));
    }

    @Test
    public void declinedCoarserLevelOpensNothing() throws Exception {
        confirmAnswer = false;
        controller((c, id, lvl, ax) -> { opens.add("x"); throw new PlaneTooLargeException("big; try pyramid level 2", 9, 1); },
                Collections.<ImagePlus>emptyList()).openRequested("a", 0);
        await(() -> log.contains("confirm"));
        Thread.sleep(100);
        assertEquals(1, opens.size());
    }

    @Test
    public void axisErrorAsksForOverrideAndRetriesWithIt() throws Exception {
        axesAnswer = " tzyx ";
        List<String> overrides = new CopyOnWriteArrayList<>();
        controller((c, id, lvl, ax) -> {
            overrides.add(String.valueOf(ax));
            if (ax == null) throw new IllegalArgumentException("Tensor has 4 unlabeled axes");
            return image(id, lvl);
        }, Collections.<ImagePlus>emptyList()).openRequested("a", 0);
        await(() -> log.contains("show:a"));
        assertEquals(Arrays.asList("null", "tzyx"), overrides);
    }

    @Test
    public void cancelledAxisDialogStops() throws Exception {
        axesAnswer = null;
        controller((c, id, lvl, ax) -> { opens.add("x"); throw new IllegalArgumentException("unlabeled axes"); },
                Collections.<ImagePlus>emptyList()).openRequested("a", 0);
        await(() -> log.contains("axes"));
        Thread.sleep(100);
        assertEquals(1, opens.size());
    }

    @Test
    public void otherErrorShowsMessage() throws Exception {
        controller((c, id, lvl, ax) -> { throw new IllegalStateException("kaput"); },
                Collections.<ImagePlus>emptyList()).openRequested("a", 0);
        await(() -> log.contains("error:Could not open a"));
    }

    @Test
    public void roisWithoutOpenImageTellsUser() throws Exception {
        controller(null, Arrays.asList(image("other", 0))).roisRequested("a");
        assertTrue(log.contains("error:Load ROIs"));
    }

    @Test
    public void pickPrefersFirstMatchingImage() {
        ImagePlus a1 = image("a", 0), a2 = image("a", 1), b = image("b", 0);
        assertSame(a2, TensorBrowserController.pick(Arrays.asList(b, a2, a1), "a"));
        assertNull(TensorBrowserController.pick(Arrays.asList(b), "a"));
        assertNull(TensorBrowserController.pick(Arrays.asList(new ImagePlus()), "a"));
    }

    @Test
    public void roiLoadFailureIsReported() throws Exception {
        controller(null, Arrays.asList(image("a", 0))).roisRequested("a");
        await(() -> log.contains("error:Load ROIs"));
    }

    @Test
    public void summaryMentionsSkippedAndTruncated() {
        RoiLoader.Result r = new RoiLoader.Result(new ArrayList<ij.gui.Roi>(),
                Collections.singletonMap("unsupported ROI shape 'mask'", 2), 2, true);
        String s = TensorBrowserController.summary(r, 0);
        assertTrue(s, s.contains("Loaded 0 ROIs") && s.contains("skipped 2") && s.contains("truncated"));
    }
}
