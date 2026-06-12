package dev.arcp.middleware.spring.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Drives the package-private {@code SpringWebSocketTransport} (reflectively constructed) through
 * its frame-decode, outbound-write, and close paths.
 */
class SpringWebSocketTransportCoverageTest {

  private static final String TRANSPORT_CLASS =
      "dev.arcp.middleware.spring.SpringWebSocketTransport";

  private static Transport newTransport(WebSocketSession session, ObjectMapper mapper)
      throws Exception {
    Class<?> cls = Class.forName(TRANSPORT_CLASS);
    Constructor<?> ctor = cls.getDeclaredConstructor(WebSocketSession.class, ObjectMapper.class);
    ctor.setAccessible(true);
    return (Transport) ctor.newInstance(session, mapper);
  }

  private static void invoke(Transport transport, String name, Class<?>[] sig, Object... args)
      throws Exception {
    Method method = Class.forName(TRANSPORT_CLASS).getDeclaredMethod(name, sig);
    method.setAccessible(true);
    method.invoke(transport, args);
  }

  @Test
  void deliverParsesFramesAndDropsMalformedOnes() throws Exception {
    SpringTestSupport.WebSessionRecorder rec = new SpringTestSupport.WebSessionRecorder();
    Transport transport = newTransport(SpringTestSupport.webSocketSession("deliver", rec), null);
    SpringTestSupport.CollectingSubscriber sub = new SpringTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    invoke(transport, "deliver", new Class<?>[] {String.class}, SpringTestSupport.pingFrame());
    SpringTestSupport.awaitTrue("envelope delivery", () -> sub.items.size() == 1);
    assertThat(sub.items.get(0).type()).isEqualTo("session.ping");

    invoke(transport, "deliver", new Class<?>[] {String.class}, "{malformed");
    invoke(transport, "completeInbound", new Class<?>[0]);
    assertThat(sub.awaitCompleted()).isTrue();
    assertThat(sub.items).hasSize(1);
  }

  @Test
  void failInboundSurfacesAsSubscriberError() throws Exception {
    SpringTestSupport.WebSessionRecorder rec = new SpringTestSupport.WebSessionRecorder();
    Transport transport = newTransport(SpringTestSupport.webSocketSession("failing", rec), null);
    SpringTestSupport.CollectingSubscriber sub = new SpringTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    invoke(
        transport, "failInbound", new Class<?>[] {Throwable.class}, new RuntimeException("boom"));
    assertThat(sub.awaitErrored()).isTrue();
    assertThat(sub.error.get()).hasMessage("boom");
  }

  @Test
  void sendWritesJsonTextFrames() throws Exception {
    SpringTestSupport.WebSessionRecorder rec = new SpringTestSupport.WebSessionRecorder();
    Transport transport =
        newTransport(SpringTestSupport.webSocketSession("send", rec), ArcpMapper.create());

    transport.send(SpringTestSupport.pingEnvelope());

    assertThat(rec.sent).hasSize(1);
    assertThat(((TextMessage) rec.sent.get(0)).getPayload()).contains("\"session.ping\"");
  }

  @Test
  void sendFailureSurfacesAsUncheckedIoException() throws Exception {
    SpringTestSupport.WebSessionRecorder rec = new SpringTestSupport.WebSessionRecorder();
    rec.throwOnSend = true;
    Transport transport = newTransport(SpringTestSupport.webSocketSession("send-fail", rec), null);

    assertThatThrownBy(() -> transport.send(SpringTestSupport.pingEnvelope()))
        .isInstanceOf(UncheckedIOException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void closeClosesSessionAndInbound() throws Exception {
    SpringTestSupport.WebSessionRecorder rec = new SpringTestSupport.WebSessionRecorder();
    Transport transport = newTransport(SpringTestSupport.webSocketSession("close", rec), null);
    SpringTestSupport.CollectingSubscriber sub = new SpringTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    transport.close();

    assertThat(rec.closed).isTrue();
    assertThat(sub.awaitCompleted()).isTrue();
  }

  @Test
  void closeSwallowsSessionCloseFailure() throws Exception {
    SpringTestSupport.WebSessionRecorder rec = new SpringTestSupport.WebSessionRecorder();
    rec.throwOnClose = true;
    Transport transport = newTransport(SpringTestSupport.webSocketSession("close-fail", rec), null);
    SpringTestSupport.CollectingSubscriber sub = new SpringTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    transport.close();

    assertThat(rec.closed).isFalse();
    assertThat(sub.awaitCompleted()).isTrue();
  }
}
