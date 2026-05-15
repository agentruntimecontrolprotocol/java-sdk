package dev.arcp.middleware.vertx;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import io.vertx.core.http.ServerWebSocket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/** Bridges a Vert.x {@link ServerWebSocket} to the ARCP {@link Transport} SPI. */
final class VertxWebSocketTransport implements Transport {

    private final ServerWebSocket socket;
    private final ObjectMapper mapper;
    private final SubmissionPublisher<Envelope> inbound;

    VertxWebSocketTransport(ServerWebSocket socket, ObjectMapper mapper) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.mapper = mapper != null ? mapper : ArcpMapper.shared();
        this.inbound = new SubmissionPublisher<>(
                Executors.newVirtualThreadPerTaskExecutor(), 1024);
    }

    void deliver(String frame) {
        try {
            inbound.submit(mapper.readValue(frame, Envelope.class));
        } catch (IOException ignored) {
            // Malformed frame; runtime sees nothing.
        }
    }

    void completeInbound() {
        inbound.close();
    }

    void failInbound(Throwable t) {
        inbound.closeExceptionally(t);
    }

    @Override
    public void send(Envelope envelope) {
        try {
            String json = mapper.writeValueAsString(envelope);
            socket.writeTextMessage(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Flow.Publisher<Envelope> incoming() {
        return inbound;
    }

    @Override
    public void close() {
        socket.close();
        inbound.close();
    }
}
