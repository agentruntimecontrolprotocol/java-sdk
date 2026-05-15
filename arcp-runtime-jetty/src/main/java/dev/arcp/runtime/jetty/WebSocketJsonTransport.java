package dev.arcp.runtime.jetty;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import jakarta.websocket.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges a Jakarta WebSocket {@link Session} to the ARCP {@link Transport}
 * SPI. JSON envelopes ride as text frames per §4.1.
 */
final class WebSocketJsonTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(WebSocketJsonTransport.class);

    private final Session session;
    private final ObjectMapper mapper;
    private final SubmissionPublisher<Envelope> inbound;

    WebSocketJsonTransport(Session session, ObjectMapper mapper) {
        this.session = Objects.requireNonNull(session, "session");
        this.mapper = mapper != null ? mapper : ArcpMapper.shared();
        this.inbound = new SubmissionPublisher<>(
                Executors.newVirtualThreadPerTaskExecutor(), 1024);
    }

    void deliver(String frame) {
        try {
            Envelope env = mapper.readValue(frame, Envelope.class);
            inbound.submit(env);
        } catch (IOException e) {
            log.warn("malformed envelope frame: {}", e.getMessage());
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
            session.getBasicRemote().sendText(json);
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
        try {
            session.close();
        } catch (IOException ignored) {
            // best-effort close
        }
        inbound.close();
    }
}
