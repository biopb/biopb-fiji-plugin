package io.github.jiyuuchc.biopb.fiji;

import biopb.tensor.TensorFlightClient;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link TensorBrowserPanel.Connection} shared by the panel and every image opened through it
 * (lazy stacks read through the client for as long as they are open). Starts with one reference
 * (the creator's); the delegate closes when the count reaches zero.
 */
public final class RefCountedConnection implements TensorBrowserPanel.Connection {
    private static final AtomicLong IDS = new AtomicLong();
    private static final Executor DAEMON = r -> {
        Thread t = new Thread(r, "biopb-close");
        t.setDaemon(true);
        t.start();
    };

    private final TensorBrowserPanel.Connection delegate;
    private final Executor closer;
    private final String id = "conn-" + IDS.incrementAndGet();
    private int refs = 1;

    /** Wraps {@code delegate}, closing it on a daemon thread when the last reference goes. */
    public RefCountedConnection(TensorBrowserPanel.Connection delegate) {
        this(delegate, DAEMON);
    }

    /** As above, closing the delegate through {@code closer}. */
    public RefCountedConnection(TensorBrowserPanel.Connection delegate, Executor closer) {
        this.delegate = delegate;
        this.closer = closer;
    }

    /** Unique id of this connection, stored on images opened through it. */
    public String id() { return id; }

    /** Takes a reference; false if the connection is already closed. */
    public synchronized boolean acquire() {
        if (refs == 0) {
            return false;
        }
        refs++;
        return true;
    }

    /** Drops a reference; closes the delegate on the last one. Extra calls are ignored. */
    @Override
    public void close() {
        synchronized (this) {
            if (refs == 0) {
                return;
            }
            if (--refs > 0) {
                return;
            }
        }
        closer.execute(delegate::close);
    }

    /** References currently held. */
    synchronized int refCount() { return refs; }

    @Override public CatalogClient catalog() { return delegate.catalog(); }

    @Override public TensorFlightClient flight() { return delegate.flight(); }
}
