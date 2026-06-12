package dev.arcp.runtime.jetty;

import dev.arcp.runtime.ArcpRuntime;
import jakarta.servlet.DispatcherType;
import jakarta.websocket.server.ServerEndpointConfig;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.VirtualThreadPool;

/** Embedded Jetty 12 server exposing an ARCP runtime over {@code /arcp} as a WebSocket endpoint. */
public final class ArcpJettyServer implements AutoCloseable {

  private final ArcpRuntime runtime;
  private final String path;
  private final int port;
  private final List<String> allowedHosts;
  private final Server server;

  private ArcpJettyServer(Builder b) {
    this.runtime = Objects.requireNonNull(b.runtime, "runtime");
    this.path = b.path;
    this.port = b.port;
    this.allowedHosts = b.allowedHosts;
    VirtualThreadPool pool = new VirtualThreadPool();
    this.server = new Server(pool);
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(port);
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler("/");
    server.setHandler(context);

    // §14: reject upgrades from a non-allowlisted Host with 403 before the WebSocket handshake, so
    // allowedHosts is an enforced control rather than a silent no-op (#99).
    if (!allowedHosts.isEmpty()) {
      context.addFilter(
          new FilterHolder(new HostAllowlistFilter(allowedHosts)),
          "/*",
          EnumSet.of(DispatcherType.REQUEST));
    }

    JakartaWebSocketServletContainerInitializer.configure(
        context,
        (servletContext, container) -> {
          ServerEndpointConfig endpointConfig =
              ServerEndpointConfig.Builder.create(ArcpJettyEndpoint.class, path)
                  .configurator(
                      new ServerEndpointConfig.Configurator() {
                        @Override
                        public <T> T getEndpointInstance(Class<T> endpointClass) {
                          return endpointClass.cast(new ArcpJettyEndpoint());
                        }
                      })
                  .build();
          endpointConfig.getUserProperties().put(ArcpJettyEndpoint.RUNTIME_KEY, runtime);
          container.addEndpoint(endpointConfig);
        });
  }

  /**
   * Returns a builder for a server that exposes {@code runtime} over WebSocket.
   *
   * @param runtime the ARCP runtime that accepted sessions are handed to
   * @return a new builder with the default {@code /arcp} path and an ephemeral port
   */
  public static Builder builder(ArcpRuntime runtime) {
    return new Builder(runtime);
  }

  /**
   * Starts the embedded Jetty server and binds the configured port.
   *
   * @return this server, for chaining
   * @throws Exception if Jetty fails to start or the port cannot be bound
   */
  public ArcpJettyServer start() throws Exception {
    server.start();
    return this;
  }

  /**
   * Returns the loopback {@code ws://} URI of the mounted endpoint, reflecting the actually bound
   * port.
   *
   * @return the WebSocket URI clients connect to
   */
  public URI uri() {
    int boundPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    return URI.create("ws://127.0.0.1:" + boundPort + path);
  }

  /**
   * Returns the TCP port the server is bound to, which differs from the configured port when an
   * ephemeral port ({@code 0}) was requested.
   *
   * @return the bound port
   */
  public int port() {
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @Override
  public void close() throws Exception {
    server.stop();
  }

  /** Builder for {@link ArcpJettyServer}. */
  public static final class Builder {
    private final ArcpRuntime runtime;
    private String path = "/arcp";
    private int port = 0;
    private List<String> allowedHosts = List.of();

    Builder(ArcpRuntime runtime) {
      this.runtime = runtime;
    }

    /**
     * Sets the request path the WebSocket endpoint is mounted at; defaults to {@code /arcp}.
     *
     * @param path the endpoint path
     * @return this builder
     */
    public Builder path(String path) {
      this.path = path;
      return this;
    }

    /**
     * Sets the TCP port to bind; defaults to {@code 0}, which picks an ephemeral port.
     *
     * @param port the port to bind
     * @return this builder
     */
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /**
     * Sets the {@code Host} header allowlist; requests from any other host are rejected with HTTP
     * 403 before the WebSocket handshake (§14). An empty list (the default) disables the check.
     *
     * @param hosts the allowed {@code Host} header values
     * @return this builder
     */
    public Builder allowedHosts(List<String> hosts) {
      this.allowedHosts = List.copyOf(hosts);
      return this;
    }

    /**
     * Builds the configured server; call {@link ArcpJettyServer#start()} to bind it.
     *
     * @return the configured, not-yet-started server
     */
    public ArcpJettyServer build() {
      return new ArcpJettyServer(this);
    }
  }
}
