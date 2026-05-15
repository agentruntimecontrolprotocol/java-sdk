package dev.arcp.runtime.jetty;

import dev.arcp.runtime.ArcpRuntime;
import jakarta.websocket.server.ServerEndpointConfig;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.VirtualThreadPool;

/**
 * Embedded Jetty 12 server exposing an ARCP runtime over {@code /arcp} as a
 * WebSocket endpoint.
 */
public final class ArcpJettyServer implements AutoCloseable {

    private final ArcpRuntime runtime;
    private final String path;
    private final int port;
    private final Server server;

    private ArcpJettyServer(Builder b) {
        this.runtime = Objects.requireNonNull(b.runtime, "runtime");
        this.path = b.path;
        this.port = b.port;
        VirtualThreadPool pool = new VirtualThreadPool();
        this.server = new Server(pool);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler("/");
        server.setHandler(context);

        JakartaWebSocketServletContainerInitializer.configure(
                context,
                (servletContext, container) -> {
                    ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder
                            .create(ArcpJettyEndpoint.class, path)
                            .configurator(new ServerEndpointConfig.Configurator() {
                                @Override
                                public <T> T getEndpointInstance(Class<T> endpointClass) {
                                    return endpointClass.cast(new ArcpJettyEndpoint());
                                }
                            })
                            .build();
                    endpointConfig.getUserProperties().put(
                            ArcpJettyEndpoint.RUNTIME_KEY, runtime);
                    container.addEndpoint(endpointConfig);
                });
    }

    public static Builder builder(ArcpRuntime runtime) {
        return new Builder(runtime);
    }

    public ArcpJettyServer start() throws Exception {
        server.start();
        return this;
    }

    public URI uri() {
        int boundPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        return URI.create("ws://127.0.0.1:" + boundPort + path);
    }

    public int port() {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }

    public static final class Builder {
        private final ArcpRuntime runtime;
        private String path = "/arcp";
        private int port = 0;
        private List<String> allowedHosts = List.of();

        Builder(ArcpRuntime runtime) {
            this.runtime = runtime;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder allowedHosts(List<String> hosts) {
            this.allowedHosts = List.copyOf(hosts);
            return this;
        }

        public ArcpJettyServer build() {
            return new ArcpJettyServer(this);
        }
    }
}
