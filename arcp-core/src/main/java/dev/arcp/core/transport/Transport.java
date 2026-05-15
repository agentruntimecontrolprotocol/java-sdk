package dev.arcp.core.transport;

import dev.arcp.core.wire.Envelope;
import java.util.concurrent.Flow;

/**
 * Bidirectional envelope channel.
 *
 * <p>{@link #send(Envelope)} delivers one envelope to the peer.
 * {@link #incoming()} exposes the inbound stream as a {@link Flow.Publisher}.
 * {@link #close()} releases resources.
 */
public interface Transport extends AutoCloseable {
    void send(Envelope envelope);

    Flow.Publisher<Envelope> incoming();

    @Override
    void close();
}
