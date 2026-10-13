package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The gate on adding: connected, local, not already adding. */
public class DropPolicyTest {

    @Test
    public void localHostsAreLocal() {
        assertTrue(DropPolicy.isLocalUrl("grpc://localhost:8815"));
        assertTrue(DropPolicy.isLocalUrl("grpc+tls://127.0.0.1:8815"));
        assertTrue(DropPolicy.isLocalUrl("grpc://[::1]:8815"));
        assertTrue(DropPolicy.isLocalUrl("localhost:8815"));
    }

    @Test
    public void aURLWithNoHostIsLocalAsInNapari() {
        assertTrue(DropPolicy.isLocalUrl("grpc://:8815"));
        assertTrue(DropPolicy.isLocalUrl("file:///tmp/x"));
    }

    @Test
    public void otherHostsAreRemote() {
        assertFalse(DropPolicy.isLocalUrl("grpc://example.com:8815"));
        assertFalse(DropPolicy.isLocalUrl("grpc://10.0.0.5:8815"));
        assertFalse(DropPolicy.isLocalUrl("grpc://ip6-localhost:8815")); // names are never resolved
        assertFalse(DropPolicy.isLocalUrl(null));
    }

    @Test
    public void reasonsFollowNapariInOrder() {
        String local = "grpc://localhost:8815";
        assertEquals(DropPolicy.NOT_CONNECTED, DropPolicy.decide(true, true, local, false).getReason());
        assertEquals(DropPolicy.NOT_CONNECTED, DropPolicy.decide(false, false, local, false).getReason());
        assertEquals(DropPolicy.REMOTE, DropPolicy.decide(false, true, "grpc://example.com:1", false).getReason());
        assertEquals(DropPolicy.REMOTE, DropPolicy.decide(false, true, null, false).getReason());
        assertEquals(DropPolicy.REMOTE, DropPolicy.decide(false, true, "", false).getReason());
        assertEquals(DropPolicy.ADDING, DropPolicy.decide(false, true, local, true).getReason());
        DropPolicy.Decision ok = DropPolicy.decide(false, true, local, false);
        assertTrue(ok.isOk());
        assertEquals(DropPolicy.READY, ok.getReason());
        assertFalse(DropPolicy.decide(false, true, local, true).isOk());
    }

    @Test
    public void remoteBeatsAdding() {
        assertEquals(DropPolicy.REMOTE, DropPolicy.decide(false, true, "grpc://example.com:1", true).getReason());
    }
}
