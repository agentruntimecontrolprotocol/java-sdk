package dev.arcp.core.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.wire.Envelope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TransportCoverageTest {

  @Test
  void memoryTransportDeliversToPeerAndRejectsClosedSends() throws Exception {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
    pair.client().incoming().subscribe(queueingSubscriber(received));

    Envelope envelope = envelope("session.ping");
    pair.runtime().send(envelope);

    assertThat(received.poll(2, TimeUnit.SECONDS)).isEqualTo(envelope);
    pair.client().close();
    assertThatThrownBy(() -> pair.runtime().send(envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("peer transport closed");
    pair.runtime().close();
    pair.runtime().close();
  }

  @Test
  void stdioTransportReadsValidLinesDropsBadLinesAndWritesOneLine() throws Exception {
    String valid =
        """
        {"arcp":"1.1","id":"m_stdio_in","type":"session.ping","payload":{}}
        not-json

        """;
    ByteArrayInputStream in = new ByteArrayInputStream(valid.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    StdioTransport transport = new StdioTransport(in, out);
    BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
    transport.incoming().subscribe(queueingSubscriber(received));

    transport.start();
    Envelope inbound = received.poll(2, TimeUnit.SECONDS);
    assertThat(inbound).isNotNull();
    assertThat(inbound.id()).isEqualTo(MessageId.of("m_stdio_in"));

    Envelope outbound = envelope("session.pong");
    transport.send(outbound);
    String written = out.toString(StandardCharsets.UTF_8);
    assertThat(written).contains("\"type\":\"session.pong\"");
    assertThat(written).endsWith("\n");

    transport.close();
    transport.close();
    assertThatThrownBy(() -> transport.send(outbound))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transport closed");
  }

  private static Envelope envelope(String type) {
    return Envelope.builder(type)
        .id(MessageId.of("m_" + type.replace('.', '_')))
        .sessionId(SessionId.of("sess_transport"))
        .payload(JsonNodeFactory.instance.objectNode())
        .build();
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
}
