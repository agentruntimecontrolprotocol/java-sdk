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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side ARCP {@link Transport} backed by the JDK {@link java.net.http.WebSocket}. JSON
 * envelopes ride as text frames; multi-part text frames are reassembled per {@code last} delivery.
 */
public final class WebSocketTransport implements Transport {

  private static final Logger log = LoggerFactory.getLogger(WebSocketTransport.class);

  private volatile @Nullable WebSocket socket;
  private final ObjectMapper mapper;
  private final SubmissionPublisher<Envelope> inbound;
  private final ExecutorService inboundExecutor;
  private final StringBuilder partial = new StringBuilder();
  private final ReentrantLock writeLock = new ReentrantLock();
  private final @Nullable HttpClient ownedHttpClient;

  private WebSocketTransport(ObjectMapper mapper, @Nullable HttpClient ownedHttpClient) {
    this.mapper = mapper;
    this.ownedHttpClient = ownedHttpClient;
    this.inboundExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.inbound = new SubmissionPublisher<>(inboundExecutor, 1024);
  }

  void attachSocket(WebSocket socket) {
    this.socket = socket;
  }

  /**
   * Opens a WebSocket connection to {@code uri} with no extra headers, the shared {@link
   * ArcpMapper}, and a 10-second connect timeout.
   *
   * @param uri the {@code ws://} or {@code wss://} endpoint of the ARCP runtime
   * @return a connected transport ready for {@link #send} and {@link #incoming()}
   * @throws InterruptedException if the calling thread is interrupted while awaiting the WebSocket
   *     handshake
   */
  public static WebSocketTransport connect(URI uri) throws InterruptedException {
    return connect(uri, Map.of(), ArcpMapper.shared(), Duration.ofSeconds(10));
  }

  /**
   * Opens a WebSocket connection to {@code uri} and returns a connected transport. The transport
   * owns the underlying {@link HttpClient}; {@link #close()} releases it.
   *
   * @param uri the {@code ws://} or {@code wss://} endpoint of the ARCP runtime
   * @param headers extra HTTP headers sent with the upgrade request (e.g. authentication)
   * @param mapper Jackson mapper used to encode and decode {@link Envelope} frames
   * @param timeout maximum time to wait for the WebSocket handshake to complete
   * @return a connected transport ready for {@link #send} and {@link #incoming()}
   * @throws InterruptedException if the calling thread is interrupted while awaiting the WebSocket
   *     handshake
   */
  public static WebSocketTransport connect(
      URI uri, Map<String, String> headers, ObjectMapper mapper, Duration timeout)
      throws InterruptedException {
    HttpClient httpClient = HttpClient.newHttpClient();
    WebSocketTransport transport = new WebSocketTransport(mapper, httpClient);
    WebSocket.Builder builder = httpClient.newWebSocketBuilder();
    for (var entry : headers.entrySet()) {
      builder.header(entry.getKey(), entry.getValue());
    }
    CompletableFuture<WebSocket> stage =
        builder.buildAsync(
            uri,
            new WebSocket.Listener() {
              @Override
              public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
              }

              @Override
              public @Nullable CompletionStage<?> onText(
                  WebSocket webSocket, CharSequence data, boolean last) {
                transport.handleText(data, last);
                webSocket.request(1);
                return null;
              }

              @Override
              public @Nullable CompletionStage<?> onClose(
                  WebSocket webSocket, int statusCode, String reason) {
                transport.inbound.close();
                return null;
              }

              @Override
              public void onError(WebSocket webSocket, Throwable error) {
                transport.inbound.closeExceptionally(error);
              }
            });
    WebSocket ws;
    try {
      ws = stage.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause =
          e.getCause() instanceof CompletionException ce ? ce.getCause() : e.getCause();
      try {
        httpClient.close();
      } catch (RuntimeException ignored) {
        // best-effort
      }
      throw new IllegalStateException("WebSocket connect failed: " + cause, cause);
    } catch (java.util.concurrent.TimeoutException e) {
      try {
        httpClient.close();
      } catch (RuntimeException ignored) {
        // best-effort
      }
      throw new IllegalStateException("WebSocket connect timed out", e);
    }
    transport.attachSocket(ws);
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
    WebSocket ws = socket;
    if (ws == null) {
      throw new IllegalStateException("WebSocketTransport.send called before socket attached");
    }
    writeLock.lock();
    try {
      String json = mapper.writeValueAsString(envelope);
      ws.sendText(json, true).get(5, TimeUnit.SECONDS);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while sending", e);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException("send failed", e);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public Flow.Publisher<Envelope> incoming() {
    return inbound;
  }

  @Override
  public void close() {
    WebSocket ws = socket;
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (java.util.concurrent.ExecutionException
          | java.util.concurrent.TimeoutException ignored) {
        // best-effort close
      }
    }
    inbound.close();
    inboundExecutor.shutdown();
    if (ownedHttpClient != null) {
      try {
        ownedHttpClient.close();
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
  }
}
