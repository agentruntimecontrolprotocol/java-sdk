package dev.arcp.core.transport;

import dev.arcp.core.wire.Envelope;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Paired in-memory transport. {@link #pair()} returns two endpoints whose
 * {@link #send} feeds the other's {@link #incoming}.
 */
public final class MemoryTransport implements Transport {

    private final SubmissionPublisher<Envelope> inbound;
    private volatile MemoryTransport peer;
    private volatile boolean closed;

    private MemoryTransport(SubmissionPublisher<Envelope> inbound) {
        this.inbound = inbound;
    }

    public static MemoryTransport[] pair() {
        // Per-transport publishers using virtual-thread executors keep the
        // dispatch hop off the caller's thread and out of any platform pool.
        var aPub = new SubmissionPublisher<Envelope>(
                Executors.newVirtualThreadPerTaskExecutor(), 1024);
        var bPub = new SubmissionPublisher<Envelope>(
                Executors.newVirtualThreadPerTaskExecutor(), 1024);
        MemoryTransport a = new MemoryTransport(aPub);
        MemoryTransport b = new MemoryTransport(bPub);
        a.peer = b;
        b.peer = a;
        return new MemoryTransport[] {a, b};
    }

    @Override
    public void send(Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (closed) {
            throw new IllegalStateException("transport closed");
        }
        MemoryTransport p = peer;
        if (p == null || p.closed) {
            throw new IllegalStateException("peer transport closed");
        }
        p.inbound.submit(envelope);
    }

    @Override
    public Flow.Publisher<Envelope> incoming() {
        return inbound;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        inbound.close();
    }
}
