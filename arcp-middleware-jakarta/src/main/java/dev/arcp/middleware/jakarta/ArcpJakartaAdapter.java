package dev.arcp.middleware.jakarta;

import dev.arcp.runtime.ArcpRuntime;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import java.util.List;
import java.util.Objects;

/**
 * Bridges {@link ArcpRuntime} to a Jakarta WebSocket {@link ServerEndpointConfig}
 * suitable for {@code container.addEndpoint(config)}.
 *
 * <p>Consumers running their own Servlet container construct one of these and
 * register the resulting config:
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
        ServerEndpointConfig.Configurator configurator = new ServerEndpointConfig.Configurator() {
            @Override
            public <T> T getEndpointInstance(Class<T> endpointClass) {
                return endpointClass.cast(new ArcpJakartaEndpoint());
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
                    ServerEndpointConfig sec,
                    HandshakeRequest request,
                    HandshakeResponse response) {
                if (allowedHosts.isEmpty()) {
                    return;
                }
                List<String> hosts = request.getHeaders().get("Host");
                if (hosts == null || hosts.isEmpty()
                        || !allowedHosts.contains(hosts.get(0))) {
                    // Signal handshake rejection by stripping accept fields.
                    response.getHeaders().clear();
                }
            }
        };
        ServerEndpointConfig config = ServerEndpointConfig.Builder
                .create(ArcpJakartaEndpoint.class, path)
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
