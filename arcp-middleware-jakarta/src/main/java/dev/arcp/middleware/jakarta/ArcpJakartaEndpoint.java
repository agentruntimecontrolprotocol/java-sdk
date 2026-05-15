package dev.arcp.middleware.jakarta;

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
 * Jakarta WebSocket endpoint that adapts every accepted session into an ARCP
 * {@link Transport}.
 *
 * <p>The {@link ArcpRuntime} instance is sourced from the endpoint
 * configuration's {@link EndpointConfig#getUserProperties() user properties}
 * under {@link #RUNTIME_KEY}. The companion {@link ArcpJakartaAdapter} fills
 * this in for the common embedded case.
 */
public final class ArcpJakartaEndpoint extends Endpoint {

    private static final Logger log = LoggerFactory.getLogger(ArcpJakartaEndpoint.class);

    public static final String RUNTIME_KEY = ArcpRuntime.class.getName();

    private final Map<String, JakartaWebSocketTransport> transports = new ConcurrentHashMap<>();

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
        JakartaWebSocketTransport transport =
                new JakartaWebSocketTransport(session, runtime.mapper());
        transports.put(session.getId(), transport);
        session.addMessageHandler(String.class, transport::deliver);
        runtime.accept(transport);
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        JakartaWebSocketTransport transport = transports.remove(session.getId());
        if (transport != null) {
            transport.completeInbound();
        }
    }

    @Override
    public void onError(Session session, Throwable thr) {
        JakartaWebSocketTransport transport = transports.remove(session.getId());
        if (transport != null) {
            transport.failInbound(thr);
        }
        log.warn("websocket error on session {}: {}", session.getId(), thr.toString());
    }
}
