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
 * Jakarta WebSocket endpoint that adapts every accepted session into an ARCP {@link
 * dev.arcp.core.transport.Transport}.
 *
 * <p>The {@link ArcpRuntime} instance is sourced from the endpoint configuration's {@link
 * EndpointConfig#getUserProperties() user properties} under {@link #RUNTIME_KEY}. The companion
 * {@link ArcpJakartaAdapter} fills this in for the common embedded case.
 */
public final class ArcpJakartaEndpoint extends Endpoint {

  private static final Logger log = LoggerFactory.getLogger(ArcpJakartaEndpoint.class);

  /**
   * {@link EndpointConfig#getUserProperties()} key under which the {@link ArcpRuntime} rides;
   * {@link ArcpJakartaAdapter#serverEndpointConfig()} populates it automatically.
   */
  public static final String RUNTIME_KEY = ArcpRuntime.class.getName();

  private final Map<String, JakartaWebSocketTransport> transports = new ConcurrentHashMap<>();
  private final boolean hostRejected;

  /**
   * Creates an endpoint that treats the handshake's {@code Host} as allowed; containers
   * instantiating the endpoint outside {@link ArcpJakartaAdapter}'s configurator use this form.
   */
  public ArcpJakartaEndpoint() {
    this(false);
  }

  ArcpJakartaEndpoint(boolean hostRejected) {
    this.hostRejected = hostRejected;
  }

  @Override
  public void onOpen(Session session, EndpointConfig config) {
    if (hostRejected) {
      // §14: the Host was not on the allowlist; close before the runtime ever sees the session, so
      // a client that ignores Sec-WebSocket-Accept validation cannot talk to the runtime (#100).
      try {
        session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "host not allowed"));
      } catch (java.io.IOException ignored) {
        // best-effort close
      }
      return;
    }
    ArcpRuntime runtime = (ArcpRuntime) config.getUserProperties().get(RUNTIME_KEY);
    if (runtime == null) {
      log.warn("ArcpRuntime missing from EndpointConfig user properties");
      try {
        session.close(
            new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "runtime not configured"));
      } catch (java.io.IOException ignored) {
        // best-effort close
      }
      return;
    }
    JakartaWebSocketTransport transport = new JakartaWebSocketTransport(session, runtime.mapper());
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
