package io.github.jiyuuchc.biopb.fiji;

import biopb.image.RoiListResult;
import biopb.tensor.TensorFlightClient;
import java.io.IOException;

/** Seam over {@code TensorFlightClient.listRois}, so loading can be tested without a server. */
@FunctionalInterface
public interface RoiSource {

    /**
     * Read one layer of a tensor's annotations. The SDK has no paging: one call returns the
     * server's whole (capped) set and flags {@code truncated} when the cap clipped it.
     *
     * @param arrayId unversioned array id
     * @param setName set to read; empty means the client-owned sets
     */
    RoiListResult list(String arrayId, String setName) throws IOException;

    /** A source backed by a live client (reads with the connection's own credentials). */
    static RoiSource of(TensorFlightClient client) {
        return client::listRois;
    }

    /** A source backed by a client and a sealed ROI ticket (may be null). */
    static RoiSource of(TensorFlightClient client, byte[] roiTicket) {
        return (arrayId, setName) -> client.listRois(arrayId, setName, roiTicket);
    }
}
