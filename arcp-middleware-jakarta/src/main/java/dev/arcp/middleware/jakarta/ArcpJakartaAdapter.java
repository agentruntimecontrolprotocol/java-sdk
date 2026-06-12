package dev.arcp.middleware.jakarta;

import dev.arcp.runtime.ArcpRuntime;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import java.util.List;
import java.util.Objects;

/**
 * Bridges {@link ArcpRuntime} to a Jakarta WebSocket {@link ServerEndpointConfig} suitable for
 * {@code container.addEndpoint(config)}.
 *
 * <p>Consumers running their own Servlet container construct one of these and register the
 * resulting config:
 *
 * <pre>{@code
 * ArcpJakartaAdapter adapter = ArcpJakartaAdapter.builder()
 *     .runtime(runtime)
 *     .path("/arcp")
 *     .allowedHosts(List.of("agents.example.com"))
 *     .build();
 *
 * container.addEndpoint(adapter.serverEndpointConfig());
 * }</pre>
 */
public final class ArcpJakartaAdapter {

  private final ArcpRuntime runtime;
  private final String path;
  private final List<String> allowedHosts;
  private final List<String> allowedOrigins;

  private ArcpJakartaAdapter(Builder b) {
    this.runtime = Objects.requireNonNull(b.runtime, "runtime");
    this.path = Objects.requireNonNull(b.path, "path");
    this.allowedHosts = List.copyOf(b.allowedHosts);
    this.allowedOrigins = List.copyOf(b.allowedOrigins);
  }

  /**
   * Returns a new builder.
   *
   * @return a fresh builder with the default {@code /arcp} path and empty allowlists
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builds the {@link ServerEndpointConfig} ready to hand to a container.
   *
   * @return a config that mounts an ARCP WebSocket endpoint, serving JSON envelopes as text frames
   *     per §4, at the configured path
   */
  public ServerEndpointConfig serverEndpointConfig() {
    // The Jakarta WebSocket API offers no veto in modifyHandshake: clearing response headers does
    // not stop the server from completing the upgrade, so a client that ignores
    // Sec-WebSocket-Accept would still reach the runtime. Instead, record the host decision per
    // handshake and have the endpoint close the session before runtime.accept (#100). The handshake
    // and endpoint construction run on the same container thread, so a ThreadLocal reliably carries
    // the decision from modifyHandshake to getEndpointInstance.
    ThreadLocal<Boolean> hostRejected = ThreadLocal.withInitial(() -> Boolean.FALSE);
    ServerEndpointConfig.Configurator configurator =
        new ServerEndpointConfig.Configurator() {
          @Override
          public <T> T getEndpointInstance(Class<T> endpointClass) {
            boolean rejected = hostRejected.get();
            hostRejected.remove();
            return endpointClass.cast(new ArcpJakartaEndpoint(rejected));
          }

          @Override
          public boolean checkOrigin(String originHeaderValue) {
            if (allowedOrigins.isEmpty()) {
              return true;
            }
            return originHeaderValue != null && allowedOrigins.contains(originHeaderValue);
          }

          @Override
          public void modifyHandshake(
              ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            if (allowedHosts.isEmpty()) {
              hostRejected.set(Boolean.FALSE);
              return;
            }
            List<String> hosts = request.getHeaders().get("Host");
            boolean allowed =
                hosts != null && !hosts.isEmpty() && allowedHosts.contains(hosts.get(0));
            hostRejected.set(!allowed);
          }
        };
    ServerEndpointConfig config =
        ServerEndpointConfig.Builder.create(ArcpJakartaEndpoint.class, path)
            .configurator(configurator)
            .build();
    config.getUserProperties().put(ArcpJakartaEndpoint.RUNTIME_KEY, runtime);
    return config;
  }

  /**
   * Returns the runtime accepted sessions are handed to.
   *
   * @return the configured {@link ArcpRuntime}
   */
  public ArcpRuntime runtime() {
    return runtime;
  }

  /**
   * Returns the request path the endpoint is mounted at.
   *
   * @return the endpoint path, {@code /arcp} by default
   */
  public String path() {
    return path;
  }

  /**
   * Returns the {@code Host} header allowlist enforced during the handshake (§14).
   *
   * @return the allowed {@code Host} values; empty means all hosts are accepted
   */
  public List<String> allowedHosts() {
    return allowedHosts;
  }

  /**
   * Returns the {@code Origin} header allowlist checked during the handshake (§14).
   *
   * @return the allowed {@code Origin} values; empty means all origins are accepted
   */
  public List<String> allowedOrigins() {
    return allowedOrigins;
  }

  /** Builder for {@link ArcpJakartaAdapter}. */
  public static final class Builder {
    private ArcpRuntime runtime;
    private String path = "/arcp";
    private List<String> allowedHosts = List.of();
    private List<String> allowedOrigins = List.of();

    /** Creates a builder with the default {@code /arcp} path and empty allowlists. */
    public Builder() {}

    /**
     * Sets the runtime that accepted sessions are handed to; required.
     *
     * @param runtime the ARCP runtime
     * @return this builder
     */
    public Builder runtime(ArcpRuntime runtime) {
      this.runtime = runtime;
      return this;
    }

    /**
     * Sets the request path the endpoint is mounted at; defaults to {@code /arcp}.
     *
     * @param path the endpoint path
     * @return this builder
     */
    public Builder path(String path) {
      this.path = path;
      return this;
    }

    /**
     * Sets the {@code Host} header allowlist; sessions from any other host are closed before the
     * runtime sees them (§14). An empty list (the default) disables the check.
     *
     * @param hosts the allowed {@code Host} header values
     * @return this builder
     */
    public Builder allowedHosts(List<String> hosts) {
      this.allowedHosts = List.copyOf(hosts);
      return this;
    }

    /**
     * Sets the {@code Origin} header allowlist; handshakes from any other origin are refused (§14).
     * An empty list (the default) accepts all origins.
     *
     * @param origins the allowed {@code Origin} header values
     * @return this builder
     */
    public Builder allowedOrigins(List<String> origins) {
      this.allowedOrigins = List.copyOf(origins);
      return this;
    }

    /**
     * Builds the adapter.
     *
     * @return the configured adapter; pass {@link ArcpJakartaAdapter#serverEndpointConfig()} to the
     *     container
     */
    public ArcpJakartaAdapter build() {
      return new ArcpJakartaAdapter(this);
    }
  }
}
