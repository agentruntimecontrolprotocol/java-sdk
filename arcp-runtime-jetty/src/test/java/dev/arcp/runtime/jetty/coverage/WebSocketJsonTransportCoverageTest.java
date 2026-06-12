package dev.arcp.runtime.jetty.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import jakarta.websocket.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Drives the package-private {@code WebSocketJsonTransport} (reflectively constructed) through its
 * frame-decode, outbound-write, and close paths.
 */
class WebSocketJsonTransportCoverageTest {

  private static final String TRANSPORT_CLASS = "dev.arcp.runtime.jetty.WebSocketJsonTransport";

  private static Transport newTransport(Session session, ObjectMapper mapper) throws Exception {
    Class<?> cls = Class.forName(TRANSPORT_CLASS);
    Constructor<?> ctor = cls.getDeclaredConstructor(Session.class, ObjectMapper.class);
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
    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();
    Transport transport = newTransport(JettyTestSupport.session("deliver", rec), null);
    JettyTestSupport.CollectingSubscriber sub = new JettyTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    invoke(transport, "deliver", new Class<?>[] {String.class}, JettyTestSupport.pingFrame());
    JettyTestSupport.awaitTrue("envelope delivery", () -> sub.items.size() == 1);
    assertThat(sub.items.get(0).type()).isEqualTo("session.ping");

    invoke(transport, "deliver", new Class<?>[] {String.class}, "{malformed");
    invoke(transport, "completeInbound", new Class<?>[0]);
    assertThat(sub.awaitCompleted()).isTrue();
    assertThat(sub.items).hasSize(1);
  }

  @Test
  void failInboundSurfacesAsSubscriberError() throws Exception {
    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();
    Transport transport = newTransport(JettyTestSupport.session("failing", rec), null);
    JettyTestSupport.CollectingSubscriber sub = new JettyTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    invoke(
        transport, "failInbound", new Class<?>[] {Throwable.class}, new RuntimeException("boom"));
    assertThat(sub.awaitErrored()).isTrue();
    assertThat(sub.error.get()).hasMessage("boom");
  }

  @Test
  void sendWritesJsonTextFrames() throws Exception {
    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();
    Transport transport = newTransport(JettyTestSupport.session("send", rec), ArcpMapper.create());

    transport.send(JettyTestSupport.pingEnvelope());

    assertThat(rec.sentText).hasSize(1);
    assertThat(rec.sentText.get(0)).contains("\"session.ping\"");
  }

  @Test
  void sendFailureSurfacesAsUncheckedIoException() throws Exception {
    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();
    Transport transport =
        newTransport(JettyTestSupport.session("send-fail", rec, false, true), null);

    assertThatThrownBy(() -> transport.send(JettyTestSupport.pingEnvelope()))
        .isInstanceOf(UncheckedIOException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void closeClosesSessionAndInbound() throws Exception {
    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();
    Transport transport = newTransport(JettyTestSupport.session("close", rec), null);
    JettyTestSupport.CollectingSubscriber sub = new JettyTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    transport.close();

    assertThat(rec.plainCloseCalled).isTrue();
    assertThat(sub.awaitCompleted()).isTrue();
  }

  @Test
  void closeSwallowsSessionCloseFailure() throws Exception {
    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();
    Transport transport =
        newTransport(JettyTestSupport.session("close-fail", rec, true, false), null);

    transport.close();

    assertThat(rec.plainCloseCalled).isFalse();
  }
}
