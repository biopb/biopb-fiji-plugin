package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import biopb.tensor.Connection;
import biopb.tensor.TlsPinStore;
import java.time.Duration;
import org.junit.Assume;
import org.junit.Test;

/**
 * Manual integration test; skipped unless BIOPB_TEST_TLS_URL (e.g. grpc+tls://localhost:8916) is set.
 * Uses an in-memory pin store, so it never touches the shared pin file: the first connect pins the
 * server's certificate, the second (same store) must match it.
 */
public class TlsServerTest {
    @Test
    public void connectsOverTlsWithTrustOnFirstUse() {
        String url = System.getenv("BIOPB_TEST_TLS_URL");
        Assume.assumeTrue(url != null && !url.isEmpty());
        TlsPinStore pins = TlsPinStore.inMemory();
        try (Connection first = new Connection(pins); Connection again = new Connection(pins)) {
            String token = System.getenv("BIOPB_TENSOR_TOKEN");
            boolean ok = first.connect(url, token, Duration.ofSeconds(20));
            assertTrue(first.lastMessage(), ok);
            assertNotNull(first.client());
            ok = again.connect(url, token, Duration.ofSeconds(20)); // now checked against the pin
            assertTrue(again.lastMessage(), ok);
        }
    }
}
