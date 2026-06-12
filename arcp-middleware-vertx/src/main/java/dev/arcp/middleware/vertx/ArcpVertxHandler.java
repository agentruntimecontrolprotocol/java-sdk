package dev.arcp.middleware.vertx;

import dev.arcp.runtime.ArcpRuntime;
import io.vertx.core.Handler;
import io.vertx.core.http.ServerWebSocket;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x WebSocket handler that adapts each accepted {@link ServerWebSocket} into an ARCP {@link
 * dev.arcp.core.transport.Transport}.
 *
 * <p>Caller registers via {@code HttpServer.webSocketHandler(handler)}. Path matching is done by
 * inspecting {@code ws.path()} — sockets at other paths are rejected.
 */
public final class ArcpVertxHandler implements Handler<ServerWebSocket> {

  private static final Logger log = LoggerFactory.getLogger(ArcpVertxHandler.class);

  private final ArcpRuntime runtime;
  private final String path;
  private final List<String> allowedHosts;

  private ArcpVertxHandler(Builder b) {
    this.runtime = Objects.requireNonNull(b.runtime, "runtime");
    this.path = Objects.requireNonNull(b.path, "path");
    this.allowedHosts = List.copyOf(b.allowedHosts);
  }

  /**
   * Returns a new builder.
   *
   * @return a fresh builder with the default {@code /arcp} path and an empty host allowlist
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public void handle(ServerWebSocket ws) {
    if (!path.equals(ws.path())) {
      ws.close((short) 1008, "path mismatch");
      return;
    }
    if (!allowedHosts.isEmpty()) {
      String host = ws.headers().get("Host");
      if (host == null || !allowedHosts.contains(host)) {
        ws.close((short) 1008, "host not allowed");
        return;
      }
    }
    VertxWebSocketTransport transport = new VertxWebSocketTransport(ws, runtime.mapper());
    ws.textMessageHandler(transport::deliver);
    ws.closeHandler(v -> transport.completeInbound());
    ws.exceptionHandler(
        t -> {
          transport.failInbound(t);
          log.warn("vert.x websocket error: {}", t.toString());
        });
    runtime.accept(transport);
  }

  /**
   * Returns the runtime accepted sockets are handed to.
   *
   * @return the configured {@link ArcpRuntime}
   */
  public ArcpRuntime runtime() {
    return runtime;
  }

  /**
   * Returns the request path sockets must arrive at; others are closed with code {@code 1008}.
   *
   * @return the endpoint path, {@code /arcp} by default
   */
  public String path() {
    return path;
  }

  /**
   * Returns the {@code Host} header allowlist enforced on each accepted socket (§14).
   *
   * @return the allowed {@code Host} values; empty means all hosts are accepted
   */
  public List<String> allowedHosts() {
    return allowedHosts;
  }

  /** Builder for {@link ArcpVertxHandler}. */
  public static final class Builder {
    private ArcpRuntime runtime;
    private String path = "/arcp";
    private List<String> allowedHosts = List.of();

    /** Creates a builder with the default {@code /arcp} path and an empty host allowlist. */
    public Builder() {}

    /**
     * Sets the runtime that accepted sockets are handed to; required.
     *
     * @param runtime the ARCP runtime
     * @return this builder
     */
    public Builder runtime(ArcpRuntime runtime) {
      this.runtime = runtime;
      return this;
    }

    /**
     * Sets the request path served by the handler; defaults to {@code /arcp}.
     *
     * @param path the endpoint path
     * @return this builder
     */
    public Builder path(String path) {
      this.path = path;
      return this;
    }

    /**
     * Sets the {@code Host} header allowlist; sockets from any other host are closed with code
     * {@code 1008} before the runtime sees them (§14). An empty list (the default) disables the
     * check.
     *
     * @param hosts the allowed {@code Host} header values
     * @return this builder
     */
    public Builder allowedHosts(List<String> hosts) {
      this.allowedHosts = List.copyOf(hosts);
      return this;
    }

    /**
     * Builds the handler, ready to register via {@code HttpServer.webSocketHandler(handler)}.
     *
     * @return the configured handler
     */
    public ArcpVertxHandler build() {
      return new ArcpVertxHandler(this);
    }
  }
}
