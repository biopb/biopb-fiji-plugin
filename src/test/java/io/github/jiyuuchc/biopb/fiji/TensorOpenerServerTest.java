package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeNotNull;

import biopb.tensor.TensorFlightClient;
import ij.ImagePlus;
import org.junit.Test;

/**
 * Manual integration test; skipped unless BIOPB_TEST_SERVER=host:port is set. BIOPB_TEST_OME_ID must
 * name a [t,c,z,y,x] = 3x2x4x96x128 uint16 tensor with pixel value t*1000 + c*100 + z*10 + (x % 10);
 * optional BIOPB_TEST_BIG_ID names a pyramidal tensor whose level 0 is large (opened at level 1).
 */
public class TensorOpenerServerTest {

    @Test
    public void opensOmeTensor() throws Exception {
        String server = System.getenv("BIOPB_TEST_SERVER");
        String id = System.getenv("BIOPB_TEST_OME_ID");
        assumeNotNull(server, id);
        String[] hp = server.split(":");
        try (TensorFlightClient client = new TensorFlightClient(hp[0], Integer.parseInt(hp[1]))) {
            ImagePlus imp = TensorOpener.open(client, id, 0, null);
            assertEquals(2, imp.getNChannels());
            assertEquals(4, imp.getNSlices());
            assertEquals(3, imp.getNFrames());
            assertEquals(128, imp.getWidth());
            assertEquals(96, imp.getHeight());
            for (int t = 1; t <= 3; t++) for (int c = 1; c <= 2; c++) for (int z = 1; z <= 4; z++) {
                int v = imp.getStack().getProcessor(imp.getStackIndex(c, z, t)).get(13, 7);
                assertEquals((t - 1) * 1000 + (c - 1) * 100 + (z - 1) * 10 + 3, v);
            }
            assertNotNull(TensorOpener.LevelInfo.of(imp));
        }
    }

    @Test
    public void opensCoarserLevel() throws Exception {
        String server = System.getenv("BIOPB_TEST_SERVER");
        String id = System.getenv("BIOPB_TEST_BIG_ID");
        assumeNotNull(server, id);
        String[] hp = server.split(":");
        try (TensorFlightClient client = new TensorFlightClient(hp[0], Integer.parseInt(hp[1]))) {
            ImagePlus imp = TensorOpener.open(client, id, 1, null);
            TensorOpener.LevelInfo info = TensorOpener.LevelInfo.of(imp);
            assertEquals(1, info.getLevel());
            assertEquals(8192 / (int) info.getScaleX(), imp.getWidth());
        }
    }
}
