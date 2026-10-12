package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.vector.util.Text;
import org.junit.Test;

/** The adapter's pure conversions; the Flight calls themselves need a server. */
public class FlightCatalogClientTest {

    @Test
    public void anUnknownActionBecomesUnsupported() {
        RuntimeException raw = CallStatus.UNKNOWN.withDescription("Unknown action catalog_children").toRuntimeException();
        RuntimeException out = FlightCatalogClient.oldServer(raw);
        assertTrue(out instanceof UnsupportedOperationException);
        assertSame(raw, out.getCause());
    }

    @Test
    public void unimplementedBecomesUnsupported() {
        RuntimeException raw = CallStatus.UNIMPLEMENTED.toRuntimeException();
        assertTrue(FlightCatalogClient.oldServer(raw) instanceof UnsupportedOperationException);
    }

    @Test
    public void theSdksOwnUnsupportedPassesThrough() {
        RuntimeException sdk = new UnsupportedOperationException("too old");
        assertSame(sdk, FlightCatalogClient.oldServer(sdk));
    }

    @Test
    public void otherFailuresAreNotTakenForAnOldServer() {
        RuntimeException unavailable = CallStatus.UNAVAILABLE.withDescription("down").toRuntimeException();
        assertSame(unavailable, FlightCatalogClient.oldServer(unavailable));
        RuntimeException other = new IllegalStateException("Not connected");
        assertSame(other, FlightCatalogClient.oldServer(other));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void arrowValuesBecomeImmutablePlainCopies() {
        Map<String, Object> struct = new LinkedHashMap<>();
        struct.put("name", new Text("t0"));
        List<Object> nested = new ArrayList<>(Arrays.asList(new Text("x"), struct));
        Object plain = FlightCatalogClient.plain(nested);

        List<Object> list = (List<Object>) plain;
        assertEquals("x", list.get(0));
        Map<String, Object> copy = (Map<String, Object>) list.get(1);
        assertEquals("t0", copy.get("name"));
        try {
            list.add("y");
            fail("list should be immutable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        try {
            copy.put("k", "v");
            fail("map should be immutable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        nested.add("late");
        assertEquals(2, list.size()); // a copy, not a view
    }
}
