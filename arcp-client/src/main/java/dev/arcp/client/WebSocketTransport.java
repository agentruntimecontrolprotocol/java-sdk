package dev.arcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side ARCP {@link Transport} backed by the JDK
 * {@link java.net.http.HttpClient.WebSocket}. JSON envelopes ride as text
 * frames; multi-part text frames are reassembled per {@code last} delivery.
 */
public final class WebSocketTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(WebSocketTransport.class);

    private final WebSocket socket;
    private final ObjectMapper mapper;
    private final SubmissionPublisher<Envelope> inbound;
    private final StringBuilder partial = new StringBuilder();

    private WebSocketTransport(WebSocket socket, ObjectMapper mapper) {
        this.socket = socket;
        this.mapper = mapper;
        this.inbound = new SubmissionPublisher<>(
                Executors.newVirtualThreadPerTaskExecutor(), 1024);
    }

    /** Open a WebSocket connection to {@code uri} and return a connected transport. */
    public static WebSocketTransport connect(URI uri) throws InterruptedException {
        return connect(uri, Map.of(), ArcpMapper.shared(), Duration.ofSeconds(10));
    }

    public static WebSocketTransport connect(
            URI uri, Map<String, String> headers, ObjectMapper mapper, Duration timeout)
            throws InterruptedException {
        HttpClient httpClient = HttpClient.newHttpClient();
        WebSocket.Builder builder = httpClient.newWebSocketBuilder();
        for (var entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        var futureSocket = new java.util.concurrent.atomic.AtomicReference<WebSocketTransport>();
        CompletableFuture<WebSocket> stage = builder.buildAsync(uri, new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                WebSocketTransport t = futureSocket.get();
                if (t != null) {
                    t.handleText(data, last);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                WebSocketTransport t = futureSocket.get();
                if (t != null) {
                    t.inbound.close();
                }
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                WebSocketTransport t = futureSocket.get();
                if (t != null) {
                    t.inbound.closeExceptionally(error);
                }
            }
        });
        WebSocket ws;
        try {
            ws = stage.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException ce
                    ? ce.getCause() : e.getCause();
            throw new IllegalStateException("WebSocket connect failed: " + cause, cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("WebSocket connect timed out", e);
        }
        WebSocketTransport transport = new WebSocketTransport(ws, mapper);
        futureSocket.set(transport);
        return transport;
    }

    private void handleText(CharSequence data, boolean last) {
        partial.append(data);
        if (!last) {
            return;
        }
        String frame = partial.toString();
        partial.setLength(0);
        try {
            Envelope env = mapper.readValue(frame, Envelope.class);
            inbound.submit(env);
        } catch (IOException e) {
            log.warn("malformed envelope frame: {}", e.getMessage());
        }
    }

    @Override
    public void send(Envelope envelope) {
        try {
            String json = mapper.writeValueAsString(envelope);
            socket.sendText(json, true).get(5, TimeUnit.SECONDS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while sending", e);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("send failed", e);
        }
    }

    @Override
    public Flow.Publisher<Envelope> incoming() {
        return inbound;
    }

    @Override
    public void close() {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException ignored) {
            // best-effort close
        }
        inbound.close();
    }
}
