package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import biopb.tensor.TensorFlightClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RefCountedConnectionTest {
    static final class Fake implements TensorBrowserPanel.Connection {
        final List<String> events = new ArrayList<>();
        @Override public CatalogClient catalog() { return null; }
        @Override public TensorFlightClient flight() { return null; }
        @Override public void close() { events.add("closed"); }
    }

    @Test
    public void closesOnlyWhenLastReferenceGoes() {
        Fake f = new Fake();
        RefCountedConnection c = new RefCountedConnection(f, Runnable::run);
        assertTrue(c.acquire());
        assertTrue(c.acquire());
        c.close();
        c.close();
        assertTrue(f.events.isEmpty());
        c.close();
        assertEquals(1, f.events.size());
        c.close(); // extra close is harmless
        assertEquals(1, f.events.size());
        assertFalse(c.acquire());
    }
}
