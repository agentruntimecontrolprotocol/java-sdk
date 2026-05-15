package dev.arcp.runtime.jetty;

import dev.arcp.runtime.ArcpRuntime;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jakarta WebSocket endpoint that hands every accepted session to the runtime
 * via a freshly-built {@link WebSocketJsonTransport}.
 */
public final class ArcpJettyEndpoint extends Endpoint {

    private static final Logger log = LoggerFactory.getLogger(ArcpJettyEndpoint.class);

    public static final String RUNTIME_KEY = ArcpRuntime.class.getName();

    private final Map<String, WebSocketJsonTransport> transports = new ConcurrentHashMap<>();

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        ArcpRuntime runtime = (ArcpRuntime) config.getUserProperties().get(RUNTIME_KEY);
        if (runtime == null) {
            log.warn("ArcpRuntime missing from EndpointConfig user properties");
            try {
                session.close(new CloseReason(
                        CloseReason.CloseCodes.UNEXPECTED_CONDITION,
                        "runtime not configured"));
            } catch (java.io.IOException ignored) {
                // best-effort close
            }
            return;
        }
        WebSocketJsonTransport transport =
                new WebSocketJsonTransport(session, runtime.mapper());
        transports.put(session.getId(), transport);
        session.addMessageHandler(String.class, transport::deliver);
        runtime.accept(transport);
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        WebSocketJsonTransport transport = transports.remove(session.getId());
        if (transport != null) {
            transport.completeInbound();
        }
    }

    @Override
    public void onError(Session session, Throwable thr) {
        WebSocketJsonTransport transport = transports.remove(session.getId());
        if (transport != null) {
            transport.failInbound(thr);
        }
        log.warn("websocket error on session {}: {}", session.getId(), thr.toString());
    }
}
