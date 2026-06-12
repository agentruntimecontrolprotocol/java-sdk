package dev.arcp.middleware.jakarta.coverage;

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
 * Drives the package-private {@code JakartaWebSocketTransport} (reflectively constructed) through
 * its frame-decode, outbound-write, and close paths.
 */
class JakartaWebSocketTransportCoverageTest {

  private static final String TRANSPORT_CLASS =
      "dev.arcp.middleware.jakarta.JakartaWebSocketTransport";

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
    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    Transport transport = newTransport(JakartaTestSupport.session("deliver", rec), null);
    JakartaTestSupport.CollectingSubscriber sub = new JakartaTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    invoke(transport, "deliver", new Class<?>[] {String.class}, JakartaTestSupport.pingFrame());
    JakartaTestSupport.awaitTrue("envelope delivery", () -> sub.items.size() == 1);
    assertThat(sub.items.get(0).type()).isEqualTo("session.ping");

    invoke(transport, "deliver", new Class<?>[] {String.class}, "{malformed");
    invoke(transport, "completeInbound", new Class<?>[0]);
    assertThat(sub.awaitCompleted()).isTrue();
    // The malformed frame never became an envelope.
    assertThat(sub.items).hasSize(1);
  }

  @Test
  void failInboundSurfacesAsSubscriberError() throws Exception {
    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    Transport transport = newTransport(JakartaTestSupport.session("failing", rec), null);
    JakartaTestSupport.CollectingSubscriber sub = new JakartaTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    invoke(
        transport, "failInbound", new Class<?>[] {Throwable.class}, new RuntimeException("boom"));
    assertThat(sub.awaitErrored()).isTrue();
    assertThat(sub.error.get()).hasMessage("boom");
  }

  @Test
  void sendWritesJsonTextFrames() throws Exception {
    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    Transport transport =
        newTransport(JakartaTestSupport.session("send", rec), ArcpMapper.create());

    transport.send(JakartaTestSupport.pingEnvelope());

    assertThat(rec.sentText).hasSize(1);
    assertThat(rec.sentText.get(0)).contains("\"session.ping\"");
  }

  @Test
  void sendFailureSurfacesAsUncheckedIoException() throws Exception {
    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    Transport transport =
        newTransport(JakartaTestSupport.session("send-fail", rec, false, true), null);

    assertThatThrownBy(() -> transport.send(JakartaTestSupport.pingEnvelope()))
        .isInstanceOf(UncheckedIOException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void closeClosesSessionAndInbound() throws Exception {
    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    Transport transport = newTransport(JakartaTestSupport.session("close", rec), null);
    JakartaTestSupport.CollectingSubscriber sub = new JakartaTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    transport.close();

    assertThat(rec.plainCloseCalled).isTrue();
    assertThat(sub.awaitCompleted()).isTrue();
  }

  @Test
  void closeSwallowsSessionCloseFailure() throws Exception {
    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    Transport transport =
        newTransport(JakartaTestSupport.session("close-fail", rec, true, false), null);

    transport.close();

    assertThat(rec.plainCloseCalled).isFalse();
  }
}
