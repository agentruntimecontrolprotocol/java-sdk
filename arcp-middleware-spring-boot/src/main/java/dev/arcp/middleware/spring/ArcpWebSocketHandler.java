package dev.arcp.middleware.spring;

import dev.arcp.runtime.ArcpRuntime;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Spring {@link org.springframework.web.socket.WebSocketHandler} that wraps
 * each accepted session in an ARCP {@link SpringWebSocketTransport} and hands
 * it to the configured {@link ArcpRuntime}.
 */
public final class ArcpWebSocketHandler extends TextWebSocketHandler {

    private final ArcpRuntime runtime;
    private final ConcurrentHashMap<String, SpringWebSocketTransport> transports =
            new ConcurrentHashMap<>();

    public ArcpWebSocketHandler(ArcpRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SpringWebSocketTransport transport =
                new SpringWebSocketTransport(session, runtime.mapper());
        transports.put(session.getId(), transport);
        runtime.accept(transport);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SpringWebSocketTransport transport = transports.get(session.getId());
        if (transport != null) {
            transport.deliver(message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SpringWebSocketTransport transport = transports.remove(session.getId());
        if (transport != null) {
            transport.completeInbound();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        SpringWebSocketTransport transport = transports.remove(session.getId());
        if (transport != null) {
            transport.failInbound(exception);
        }
    }
}
