package dev.arcp.middleware.spring;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Rejects WebSocket upgrades whose {@code Host} header is not on the configured allowlist with HTTP
 * 403 before the handshake completes, so the runtime never sees a session from a disallowed Host
 * (§14, #99). An empty allowlist disables the check.
 */
final class HostAllowlistHandshakeInterceptor implements HandshakeInterceptor {

  private final List<String> allowedHosts;

  HostAllowlistHandshakeInterceptor(List<String> allowedHosts) {
    this.allowedHosts = List.copyOf(allowedHosts);
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      java.util.Map<String, Object> attributes) {
    if (allowedHosts.isEmpty()) {
      return true;
    }
    String host = request.getHeaders().getFirst("Host");
    if (host != null && allowedHosts.contains(host)) {
      return true;
    }
    response.setStatusCode(HttpStatus.FORBIDDEN);
    return false;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {
    // no-op
  }
}
