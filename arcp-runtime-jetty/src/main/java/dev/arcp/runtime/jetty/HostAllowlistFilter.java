package dev.arcp.runtime.jetty;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Servlet filter that rejects requests (including WebSocket upgrades) whose {@code Host} header is
 * not on the configured allowlist with HTTP 403, before the WebSocket handshake runs (§14, #99).
 */
final class HostAllowlistFilter implements Filter {

  private final List<String> allowedHosts;

  HostAllowlistFilter(List<String> allowedHosts) {
    this.allowedHosts = List.copyOf(allowedHosts);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (allowedHosts.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    if (request instanceof HttpServletRequest httpRequest
        && response instanceof HttpServletResponse httpResponse) {
      String host = httpRequest.getHeader("Host");
      if (host == null || !allowedHosts.contains(host)) {
        httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "host not allowed");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
