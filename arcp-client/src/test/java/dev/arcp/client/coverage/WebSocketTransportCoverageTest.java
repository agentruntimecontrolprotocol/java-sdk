package dev.arcp.client.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.WebSocketTransport;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link WebSocketTransport#connect} against a minimal in-process WebSocket server so the
 * real JDK handshake, frame, and close paths run: connect success (with and without headers),
 * connect refusal, handshake timeout, server-initiated close, abrupt connection loss, and
 * send-after-close failures.
 */
class WebSocketTransportCoverageTest {

  private static final String REPLY_JSON =
      "{\"arcp\":\"1.1\",\"id\":\"m_server\",\"type\":\"session.pong\",\"payload\":{}}";

  private enum Mode {
    /** Handshake, push one text frame, then answer the client's close frame. */
    NORMAL,
    /** Handshake, push one text frame, then send a server-initiated close frame. */
    SERVER_CLOSE,
    /** Handshake, push one text frame, then drop the TCP connection without a close frame. */
    ABORT,
    /** Accept the TCP connection but never answer the HTTP upgrade. */
    STALL
  }

  /** Tiny single-connection RFC 6455 server: enough framing for these tests, nothing more. */
  private static final class MiniWsServer implements AutoCloseable {
    private final ServerSocket server;
    private final Thread thread;
    private final Mode mode;
    final CompletableFuture<String> handshake = new CompletableFuture<>();
    final CompletableFuture<String> firstTextFrame = new CompletableFuture<>();

    MiniWsServer(Mode mode) throws IOException {
      this.mode = mode;
      this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      this.thread = new Thread(this::run, "mini-ws-server");
      this.thread.start();
    }

    URI uri() {
      return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/arcp");
    }

    private void run() {
      try (Socket socket = server.accept()) {
        socket.setTcpNoDelay(true);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        String head = readHead(in);
        handshake.complete(head);
        if (mode == Mode.STALL) {
          // Stay silent until the client's 250ms handshake timeout has long fired, then answer
          // with a non-101 response. WebSocketTransport's timeout path calls HttpClient.close(),
          // which blocks until the abandoned handshake operation completes, so the operation must
          // finish (an EOF instead would make the JDK retry the GET against this one-shot server
          // and hang forever). The 3s margin dwarfs the client timeout.
          Thread.sleep(3000);
          out.write(
              ("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.ISO_8859_1));
          out.flush();
          return;
        }
        out.write(handshakeResponse(head).getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        // Read frames until the scripted exit for this mode. The reply text frame is only sent
        // once the client's first frame arrives, so tests can subscribe to the inbound publisher
        // before any envelope is published (SubmissionPublisher drops items with no subscribers).
        while (true) {
          int[] frame = readFrameHeader(in);
          if (frame == null) {
            return; // peer vanished
          }
          int opcode = frame[0];
          byte[] payload = readFramePayload(in, frame[1] == 1, frame[2]);
          if (opcode == 1) {
            firstTextFrame.complete(new String(payload, StandardCharsets.UTF_8));
            writeTextFrame(out, REPLY_JSON);
            if (mode == Mode.SERVER_CLOSE) {
              writeCloseFrame(out);
              continue; // wait for the client's close echo, then fall through to EOF
            }
            if (mode == Mode.ABORT) {
              // RST instead of FIN: an abrupt reset is unambiguously an error on the client side.
              socket.setSoLinger(true, 0);
              return;
            }
          } else if (opcode == 8) {
            writeCloseFrame(out);
            return;
          }
        }
      } catch (IOException | RuntimeException ignored) {
        // Test sockets tear down unceremoniously by design.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private static String readHead(InputStream in) throws IOException {
      StringBuilder head = new StringBuilder();
      int c;
      while ((c = in.read()) >= 0) {
        head.append((char) c);
        if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
          return head.toString();
        }
      }
      throw new IOException("connection closed during handshake");
    }

    private static String handshakeResponse(String head) {
      String key = null;
      for (String line : head.split("\r\n")) {
        int colon = line.indexOf(':');
        if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Sec-WebSocket-Key")) {
          key = line.substring(colon + 1).trim();
        }
      }
      try {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        String accept =
            Base64.getEncoder()
                .encodeToString(
                    sha1.digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                            .getBytes(StandardCharsets.ISO_8859_1)));
        return "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: "
            + accept
            + "\r\n\r\n";
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }

    /** Returns {opcode, maskedFlag, payloadLength} or null on EOF. */
    private static int[] readFrameHeader(InputStream in) throws IOException {
      int b0 = in.read();
      if (b0 < 0) {
        return null;
      }
      int b1 = in.read();
      if (b1 < 0) {
        return null;
      }
      int masked = (b1 & 0x80) != 0 ? 1 : 0;
      int len = b1 & 0x7F;
      if (len == 126) {
        len = (in.read() << 8) | in.read();
      } else if (len == 127) {
        long longLen = 0;
        for (int i = 0; i < 8; i++) {
          longLen = (longLen << 8) | in.read();
        }
        len = (int) longLen;
      }
      return new int[] {b0 & 0x0F, masked, len};
    }

    private static byte[] readFramePayload(InputStream in, boolean masked, int len)
        throws IOException {
      byte[] mask = new byte[4];
      if (masked) {
        readFully(in, mask);
      }
      byte[] payload = new byte[len];
      readFully(in, payload);
      if (masked) {
        for (int i = 0; i < payload.length; i++) {
          payload[i] ^= mask[i & 3];
        }
      }
      return payload;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
      int off = 0;
      while (off < buffer.length) {
        int n = in.read(buffer, off, buffer.length - off);
        if (n < 0) {
          throw new IOException("EOF mid-frame");
        }
        off += n;
      }
    }

    private static void writeTextFrame(OutputStream out, String text) throws IOException {
      byte[] payload = text.getBytes(StandardCharsets.UTF_8);
      out.write(0x81);
      if (payload.length < 126) {
        out.write(payload.length);
      } else {
        out.write(126);
        out.write((payload.length >>> 8) & 0xFF);
        out.write(payload.length & 0xFF);
      }
      out.write(payload);
      out.flush();
    }

    private static void writeCloseFrame(OutputStream out) throws IOException {
      out.write(new byte[] {(byte) 0x88, 0x02, 0x03, (byte) 0xE8}); // 1000 normal closure
      out.flush();
    }

    @Override
    public void close() throws IOException {
      server.close();
      try {
        thread.join(TimeUnit.SECONDS.toMillis(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static Envelope pingEnvelope() {
    return new Envelope(
        Envelope.VERSION,
        MessageId.of("m_client"),
        "session.ping",
        null,
        null,
        null,
        null,
        JsonNodeFactory.instance.objectNode());
  }

  @Test
  void connectWithHeadersExchangesFramesAndRejectsSendAfterClose() throws Exception {
    try (MiniWsServer server = new MiniWsServer(Mode.NORMAL)) {
      WebSocketTransport transport =
          WebSocketTransport.connect(
              server.uri(),
              Map.of("X-Coverage", "yes"),
              ArcpMapper.shared(),
              Duration.ofSeconds(5));
      try {
        assertThat(server.handshake.get(3, TimeUnit.SECONDS)).contains("X-Coverage: yes");
        BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
        transport.incoming().subscribe(queueingSubscriber(received, null, null));
        transport.send(pingEnvelope());
        assertThat(server.firstTextFrame.get(3, TimeUnit.SECONDS)).contains("\"session.ping\"");
        Envelope inbound = received.poll(5, TimeUnit.SECONDS);
        assertThat(inbound).isNotNull();
        assertThat(inbound.id()).isEqualTo(MessageId.of("m_server"));
      } finally {
        transport.close();
      }
      assertThatThrownBy(() -> transport.send(pingEnvelope()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("send failed");
    }
  }

  @Test
  void connectWithoutHeadersCompletesOnServerInitiatedClose() throws Exception {
    try (MiniWsServer server = new MiniWsServer(Mode.SERVER_CLOSE)) {
      WebSocketTransport transport = WebSocketTransport.connect(server.uri());
      CountDownLatch completed = new CountDownLatch(1);
      transport
          .incoming()
          .subscribe(queueingSubscriber(new LinkedBlockingQueue<>(), completed, null));
      transport.send(pingEnvelope());
      assertThat(server.firstTextFrame.get(3, TimeUnit.SECONDS)).isNotNull();
      assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
      transport.close();
    }
  }

  @Test
  void abruptConnectionLossSurfacesAsInboundError() throws Exception {
    try (MiniWsServer server = new MiniWsServer(Mode.ABORT)) {
      WebSocketTransport transport =
          WebSocketTransport.connect(
              server.uri(), Map.of(), ArcpMapper.shared(), Duration.ofSeconds(5));
      CountDownLatch errored = new CountDownLatch(1);
      transport
          .incoming()
          .subscribe(queueingSubscriber(new LinkedBlockingQueue<>(), null, errored));
      transport.send(pingEnvelope());
      assertThat(errored.await(5, TimeUnit.SECONDS)).isTrue();
      transport.close(); // close after the socket already died: sendClose failure is swallowed
    }
  }

  @Test
  void connectionRefusedYieldsIllegalState() throws Exception {
    int unboundPort;
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      unboundPort = probe.getLocalPort();
    }
    URI dead = URI.create("ws://127.0.0.1:" + unboundPort + "/arcp");
    assertThatThrownBy(
            () ->
                WebSocketTransport.connect(
                    dead, Map.of(), ArcpMapper.shared(), Duration.ofSeconds(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect failed");
  }

  @Test
  void handshakeStallYieldsTimeout() throws Exception {
    try (MiniWsServer server = new MiniWsServer(Mode.STALL)) {
      assertThatThrownBy(
              () ->
                  WebSocketTransport.connect(
                      server.uri(), Map.of(), ArcpMapper.shared(), Duration.ofMillis(250)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("timed out");
    }
  }

  @Test
  void sendBeforeAttachAndCloseWithoutSocketAreSafe() throws Exception {
    Constructor<WebSocketTransport> constructor =
        WebSocketTransport.class.getDeclaredConstructor(
            com.fasterxml.jackson.databind.ObjectMapper.class, java.net.http.HttpClient.class);
    constructor.setAccessible(true);
    WebSocketTransport detached = constructor.newInstance(ArcpMapper.shared(), null);
    assertThatThrownBy(() -> detached.send(pingEnvelope()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("before socket attached");
    detached.close(); // no socket, no owned client: every optional close branch is skipped
  }

  @Test
  void sendWrapsSerializationFailuresAsUncheckedIo() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper failingMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.module.SimpleModule module =
        new com.fasterxml.jackson.databind.module.SimpleModule();
    module.addSerializer(
        Envelope.class,
        new com.fasterxml.jackson.databind.ser.std.StdSerializer<>(Envelope.class) {
          @Override
          public void serialize(
              Envelope value,
              com.fasterxml.jackson.core.JsonGenerator gen,
              com.fasterxml.jackson.databind.SerializerProvider provider)
              throws java.io.IOException {
            throw new com.fasterxml.jackson.databind.JsonMappingException(
                gen, "cannot serialize (test)");
          }
        });
    failingMapper.registerModule(module);
    WebSocketTransport transport = detachedTransport(failingMapper);
    attach(transport, new ScriptableWebSocket(CompletableFuture.completedFuture(null)));
    assertThatThrownBy(() -> transport.send(pingEnvelope()))
        .isInstanceOf(java.io.UncheckedIOException.class);
  }

  @Test
  void sendAndCloseSurfaceInterruptionWithoutSwallowingTheFlag() throws Exception {
    WebSocketTransport transport = detachedTransport(ArcpMapper.shared());
    attach(transport, new ScriptableWebSocket(new CompletableFuture<>()));
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> transport.send(pingEnvelope()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("interrupted while sending");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear before the next phase
    }

    // close(): an interrupted sendClose wait is swallowed but re-flags the thread.
    WebSocketTransport closing = detachedTransport(ArcpMapper.shared());
    attach(closing, new ScriptableWebSocket(new CompletableFuture<>()));
    Thread.currentThread().interrupt();
    try {
      closing.close();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }

    // close(): a failed sendClose is best-effort and ignored.
    WebSocketTransport failingClose = detachedTransport(ArcpMapper.shared());
    attach(
        failingClose,
        new ScriptableWebSocket(
            CompletableFuture.failedFuture(new java.io.IOException("close rejected"))));
    failingClose.close();
  }

  private static WebSocketTransport detachedTransport(
      com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
    Constructor<WebSocketTransport> constructor =
        WebSocketTransport.class.getDeclaredConstructor(
            com.fasterxml.jackson.databind.ObjectMapper.class, java.net.http.HttpClient.class);
    constructor.setAccessible(true);
    return constructor.newInstance(mapper, null);
  }

  private static void attach(WebSocketTransport transport, java.net.http.WebSocket socket)
      throws Exception {
    java.lang.reflect.Method method =
        WebSocketTransport.class.getDeclaredMethod("attachSocket", java.net.http.WebSocket.class);
    method.setAccessible(true);
    method.invoke(transport, socket);
  }

  /** WebSocket stub whose send/close futures are scripted by the test. */
  private static final class ScriptableWebSocket implements java.net.http.WebSocket {
    private final CompletableFuture<Void> outcome;

    ScriptableWebSocket(CompletableFuture<Void> outcome) {
      this.outcome = outcome;
    }

    private CompletableFuture<java.net.http.WebSocket> scripted() {
      return outcome.thenApply(v -> (java.net.http.WebSocket) this);
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendText(CharSequence data, boolean last) {
      return scripted();
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendBinary(
        java.nio.ByteBuffer data, boolean last) {
      return scripted();
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendPing(java.nio.ByteBuffer message) {
      return scripted();
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendPong(java.nio.ByteBuffer message) {
      return scripted();
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendClose(int statusCode, String reason) {
      return scripted();
    }

    @Override
    public void request(long n) {}

    @Override
    public String getSubprotocol() {
      return "";
    }

    @Override
    public boolean isOutputClosed() {
      return false;
    }

    @Override
    public boolean isInputClosed() {
      return false;
    }

    @Override
    public void abort() {}
  }

  private static Flow.Subscriber<Envelope> queueingSubscriber(
      BlockingQueue<Envelope> queue, CountDownLatch onComplete, CountDownLatch onError) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(Envelope item) {
        queue.add(item);
      }

      @Override
      public void onError(Throwable throwable) {
        if (onError != null) {
          onError.countDown();
        }
      }

      @Override
      public void onComplete() {
        if (onComplete != null) {
          onComplete.countDown();
        }
      }
    };
  }
}
