package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class TensorVirtualStackTest {

    private static final int[][] PERMS = {{2, 3, 4}, {2, 4, 3}, {3, 2, 4}, {3, 4, 2}, {4, 2, 3}, {4, 3, 2}};

    @Test
    public void indexMappingAllPermutationsOfSizes() {
        for (int[] p : PERMS) {
            int C = p[0], Z = p[1], T = p[2];
            ImagePlus imp = TensorOpener.assemble(new FakePlaneSource(C, Z, T), "x");
            assertEquals(C * Z * T, imp.getStackSize());
            assertEquals(C, imp.getNChannels());
            assertEquals(Z, imp.getNSlices());
            assertEquals(T, imp.getNFrames());
            for (int t = 1; t <= T; t++) for (int z = 1; z <= Z; z++) for (int c = 1; c <= C; c++) {
                int n = imp.getStackIndex(c, z, t);
                assertEquals(c + C * (z - 1 + Z * (t - 1)), n);
                ImageProcessor ip = imp.getStack().getProcessor(n);
                // pixel at x=3 -> (x%10)=3
                assertEquals((t - 1) * 1000 + (c - 1) * 100 + (z - 1) * 10 + 3, ip.get(3, 0));
            }
        }
    }

    @Test
    public void sliceLabels() {
        TensorVirtualStack s = new TensorVirtualStack(new FakePlaneSource(2, 3, 4));
        assertEquals("t=2 c=1 z=2", s.getSliceLabel(9)); // idx 8: c=0, z=(8/2)%3=1, t=8/6=1
    }

    @Test
    public void sliceLabelOfSingletonDimsOmitted() {
        TensorVirtualStack s = new TensorVirtualStack(new FakePlaneSource(1, 5, 1));
        assertEquals("z=3", s.getSliceLabel(3));
        assertEquals("plane", new TensorVirtualStack(new FakePlaneSource(1, 1, 1)).getSliceLabel(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void outOfRange() {
        new TensorVirtualStack(new FakePlaneSource(1, 2, 1)).getProcessor(3);
    }

    @Test
    public void processorTypes() {
        PlaneSource.Kind[] kinds = PlaneSource.Kind.values();
        Class<?>[] cls = {ByteProcessor.class, ShortProcessor.class, FloatProcessor.class, ColorProcessor.class};
        int[] depth = {8, 16, 32, 24};
        for (int i = 0; i < kinds.length; i++) {
            TensorVirtualStack s = new TensorVirtualStack(new FakePlaneSource(6, 4, 1, 1, 1, kinds[i]));
            assertEquals(cls[i], s.getProcessor(1).getClass());
            assertEquals(depth[i], s.getBitDepth());
            assertEquals(new ImagePlus("a", s).getBitDepth(), depth[i]);
        }
    }

    @Test
    public void displayRangeIsSampledNotScanned() {
        TensorVirtualStack s = new TensorVirtualStack(new FakePlaneSource(1, 1, 1));
        double[] r = s.getDisplayRange();
        assertEquals(0, r[0], 0);
        assertEquals(5, r[1], 0);
        ImageProcessor ip = s.getProcessor(1);
        assertEquals(5, ip.getMax(), 0);
        // constant plane -> non-degenerate range
        double[] r2 = TensorVirtualStack.sampleRange(new float[16], PlaneSource.Kind.GRAY32, 4, 4);
        assertEquals(1, r2[1] - r2[0], 0);
    }

    @Test
    public void cacheAvoidsRereadsAndReturnsCopies() {
        FakePlaneSource src = new FakePlaneSource(1, 6, 1);
        TensorVirtualStack s = new TensorVirtualStack(src, 2);
        short[] a = (short[]) s.getPixels(1);
        a[0] = -1; // must not corrupt the cache
        short[] b = (short[]) s.getPixels(1);
        assertNotSame(a, b);
        assertEquals(0, b[0]);
        assertEquals(1, src.reads.get());
        s.getPixels(2); s.getPixels(3); // evicts plane 1 (LRU, size 2)
        int before = src.reads.get();
        s.getPixels(1);
        assertEquals(before + 1, src.reads.get());
    }

    @Test
    public void compositeWhenMultiChannel() {
        assertTrue(TensorOpener.assemble(new FakePlaneSource(2, 1, 1), "x") instanceof CompositeImage);
        assertTrue(!(TensorOpener.assemble(new FakePlaneSource(1, 3, 1), "x") instanceof CompositeImage));
    }

    @Test
    public void concurrentReadsAreConsistent() throws Exception {
        final FakePlaneSource src = new FakePlaneSource(3, 4, 5);
        final TensorVirtualStack s = new TensorVirtualStack(src, 3);
        ExecutorService ex = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (int k = 0; k < 400; k++) {
                final int n = 1 + (k * 7) % s.getSize();
                fs.add(ex.submit(new Callable<Boolean>() {
                    @Override public Boolean call() {
                        int i = n - 1, c = i % 3, z = i / 3 % 4, t = i / 12;
                        return s.getProcessor(n).get(5, 0) == t * 1000 + c * 100 + z * 10 + 5;
                    }
                }));
            }
            for (Future<Boolean> f : fs) assertTrue(f.get());
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    public void readFailurePropagates() {
        PlaneSource bad = new FakePlaneSource(1, 1, 1) {
            @Override public Object readPlane(int t, int c, int z) { throw new IllegalStateException("boom"); }
        };
        try {
            new TensorVirtualStack(bad).getProcessor(1);
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }
    }
}
