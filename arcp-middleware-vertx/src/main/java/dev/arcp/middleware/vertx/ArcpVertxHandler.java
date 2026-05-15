package dev.arcp.middleware.vertx;

import dev.arcp.runtime.ArcpRuntime;
import io.vertx.core.Handler;
import io.vertx.core.http.ServerWebSocket;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x WebSocket handler that adapts each accepted {@link ServerWebSocket}
 * into an ARCP {@link dev.arcp.core.transport.Transport}.
 *
 * <p>Caller registers via {@code HttpServer.webSocketHandler(handler)}.
 * Path matching is done by inspecting {@code ws.path()} — sockets at other
 * paths are rejected.
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
        VertxWebSocketTransport transport =
                new VertxWebSocketTransport(ws, runtime.mapper());
        ws.textMessageHandler(transport::deliver);
        ws.closeHandler(v -> transport.completeInbound());
        ws.exceptionHandler(t -> {
            transport.failInbound(t);
            log.warn("vert.x websocket error: {}", t.toString());
        });
        runtime.accept(transport);
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

    public static final class Builder {
        private ArcpRuntime runtime;
        private String path = "/arcp";
        private List<String> allowedHosts = List.of();

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

        public ArcpVertxHandler build() {
            return new ArcpVertxHandler(this);
        }
    }
}
