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

  public static Builder builder() {
    return new Builder();
  }

  /** Build the {@link ServerEndpointConfig} ready to hand to a container. */
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
            boolean allowed = hosts != null && !hosts.isEmpty() && allowedHosts.contains(hosts.get(0));
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

  public ArcpRuntime runtime() {
    return runtime;
  }

  public String path() {
    return path;
  }

  public List<String> allowedHosts() {
    return allowedHosts;
  }

  public List<String> allowedOrigins() {
    return allowedOrigins;
  }

  public static final class Builder {
    private ArcpRuntime runtime;
    private String path = "/arcp";
    private List<String> allowedHosts = List.of();
    private List<String> allowedOrigins = List.of();

    public Builder runtime(ArcpRuntime runtime) {
      this.runtime = runtime;
      return this;
    }

    public Builder path(String path) {
      this.path = path;
      return this;
    }

    public Builder allowedHosts(List<String> hosts) {
      this.allowedHosts = List.copyOf(hosts);
      return this;
    }

    public Builder allowedOrigins(List<String> origins) {
      this.allowedOrigins = List.copyOf(origins);
      return this;
    }

    public ArcpJakartaAdapter build() {
      return new ArcpJakartaAdapter(this);
    }
  }
}
