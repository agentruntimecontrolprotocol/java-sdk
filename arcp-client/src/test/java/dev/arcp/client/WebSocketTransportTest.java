package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebSocketTransportTest {

  @Test
  void sendHandleTextAndCloseWorkAgainstAttachedSocket() throws Exception {
    WebSocketTransport transport = newTransport();
    FakeWebSocket socket = new FakeWebSocket();
    transport.attachSocket(socket);
    BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
    transport.incoming().subscribe(queueingSubscriber(received));

    Envelope outbound = envelope("session.ping", "m_out");
    transport.send(outbound);
    assertThat(socket.textFrames).hasSize(1);
    assertThat(socket.textFrames.getFirst()).contains("\"type\":\"session.ping\"");

    Method handleText =
        WebSocketTransport.class.getDeclaredMethod("handleText", CharSequence.class, boolean.class);
    handleText.setAccessible(true);
    handleText.invoke(transport, "{\"arcp\":\"1.1\",\"id\":\"m_in\",", false);
    assertThat(received.poll(100, TimeUnit.MILLISECONDS)).isNull();
    handleText.invoke(transport, "\"type\":\"session.pong\",\"payload\":{}}", true);
    assertThat(received.poll(2, TimeUnit.SECONDS)).isEqualTo(envelope("session.pong", "m_in"));

    handleText.invoke(transport, "not-json", true);
    transport.close();
    transport.close();
    assertThat(socket.closeFrames).contains(1000);
  }

  private static WebSocketTransport newTransport() throws Exception {
    Constructor<WebSocketTransport> constructor =
        WebSocketTransport.class.getDeclaredConstructor(
            com.fasterxml.jackson.databind.ObjectMapper.class, java.net.http.HttpClient.class);
    constructor.setAccessible(true);
    return constructor.newInstance(ArcpMapper.shared(), null);
  }

  private static Envelope envelope(String type, String id) {
    return new Envelope(
        Envelope.VERSION,
        MessageId.of(id),
        type,
        null,
        null,
        null,
        null,
        JsonNodeFactory.instance.objectNode());
  }

  private static Flow.Subscriber<Envelope> queueingSubscriber(BlockingQueue<Envelope> queue) {
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
      public void onError(Throwable throwable) {}

      @Override
      public void onComplete() {}
    };
  }

  private static final class FakeWebSocket implements WebSocket {
    private final List<String> textFrames = new ArrayList<>();
    private final List<Integer> closeFrames = new ArrayList<>();

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
      textFrames.add(data.toString());
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
      closeFrames.add(statusCode);
      return CompletableFuture.completedFuture(this);
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
}
