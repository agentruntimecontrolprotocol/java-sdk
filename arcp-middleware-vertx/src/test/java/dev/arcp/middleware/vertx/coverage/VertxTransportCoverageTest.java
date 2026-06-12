package dev.arcp.middleware.vertx.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import io.vertx.core.http.ServerWebSocket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Drives the package-private {@code VertxWebSocketTransport} (reflectively constructed) through
 * decode, async write success/failure observation (#110), and close paths.
 */
class VertxTransportCoverageTest {

  private static final String TRANSPORT_CLASS = "dev.arcp.middleware.vertx.VertxWebSocketTransport";

  private static Transport newTransport(ServerWebSocket socket, ObjectMapper mapper)
      throws Exception {
    Class<?> cls = Class.forName(TRANSPORT_CLASS);
    Constructor<?> ctor = cls.getDeclaredConstructor(ServerWebSocket.class, ObjectMapper.class);
    ctor.setAccessible(true);
    return (Transport) ctor.newInstance(socket, mapper);
  }

  private static void invoke(Transport transport, String name, Class<?>[] sig, Object... args)
      throws Exception {
    Method method = Class.forName(TRANSPORT_CLASS).getDeclaredMethod(name, sig);
    method.setAccessible(true);
    method.invoke(transport, args);
  }

  @Test
  void deliverParsesFramesAndDropsMalformedOnes() throws Exception {
    VertxTestSupport.WsRecorder rec = new VertxTestSupport.WsRecorder();
    Transport transport = newTransport(VertxTestSupport.serverWebSocket(rec), null);
    VertxTestSupport.CollectingSubscriber sub = new VertxTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    invoke(transport, "deliver", new Class<?>[] {String.class}, VertxTestSupport.pingFrame());
    VertxTestSupport.awaitTrue("envelope delivery", () -> sub.items.size() == 1);
    assertThat(sub.items.get(0).type()).isEqualTo("session.ping");

    invoke(transport, "deliver", new Class<?>[] {String.class}, "{malformed");
    invoke(transport, "completeInbound", new Class<?>[0]);
    assertThat(sub.awaitCompleted()).isTrue();
    assertThat(sub.items).hasSize(1);
  }

  @Test
  void failInboundSurfacesAsSubscriberError() throws Exception {
    VertxTestSupport.WsRecorder rec = new VertxTestSupport.WsRecorder();
    Transport transport = newTransport(VertxTestSupport.serverWebSocket(rec), null);
    VertxTestSupport.CollectingSubscriber sub = new VertxTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    invoke(
        transport, "failInbound", new Class<?>[] {Throwable.class}, new RuntimeException("boom"));
    assertThat(sub.awaitErrored()).isTrue();
    assertThat(sub.error.get()).hasMessage("boom");
  }

  @Test
  void sendWritesTextMessageWhenWriteSucceeds() throws Exception {
    VertxTestSupport.WsRecorder rec = new VertxTestSupport.WsRecorder();
    Transport transport = newTransport(VertxTestSupport.serverWebSocket(rec), ArcpMapper.create());

    transport.send(VertxTestSupport.pingEnvelope());

    assertThat(rec.written).hasSize(1);
    assertThat(rec.written.get(0)).contains("\"session.ping\"");
    assertThat(rec.closeCalls.get()).isZero();
  }

  @Test
  void failedWriteIsObservedAsTransportFailureAndClosesSocket() throws Exception {
    // §110: a failed async write must fail the inbound stream and close the socket so the runtime
    // notices the dead session, instead of the write being silently dropped.
    VertxTestSupport.WsRecorder rec = new VertxTestSupport.WsRecorder();
    rec.failWrites = true;
    Transport transport = newTransport(VertxTestSupport.serverWebSocket(rec), null);
    VertxTestSupport.CollectingSubscriber sub = new VertxTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    transport.send(VertxTestSupport.pingEnvelope());

    assertThat(sub.awaitErrored()).isTrue();
    assertThat(sub.error.get()).isInstanceOf(IOException.class);
    VertxTestSupport.awaitTrue("socket close after failed write", () -> rec.closeCalls.get() > 0);
    assertThat(rec.written).isEmpty();
  }

  @Test
  void serializationFailureSurfacesAsUncheckedIoException() throws Exception {
    VertxTestSupport.WsRecorder rec = new VertxTestSupport.WsRecorder();
    ObjectMapper throwingMapper =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) throws JsonProcessingException {
            throw new JsonProcessingException("serialization refused by fake") {};
          }
        };
    Transport transport = newTransport(VertxTestSupport.serverWebSocket(rec), throwingMapper);

    assertThatThrownBy(() -> transport.send(VertxTestSupport.pingEnvelope()))
        .isInstanceOf(UncheckedIOException.class)
        .hasCauseInstanceOf(IOException.class);
    assertThat(rec.written).isEmpty();
  }

  @Test
  void closeClosesSocketAndInbound() throws Exception {
    VertxTestSupport.WsRecorder rec = new VertxTestSupport.WsRecorder();
    Transport transport = newTransport(VertxTestSupport.serverWebSocket(rec), null);
    VertxTestSupport.CollectingSubscriber sub = new VertxTestSupport.CollectingSubscriber();
    transport.incoming().subscribe(sub);

    transport.close();

    assertThat(rec.closeCalls.get()).isEqualTo(1);
    assertThat(sub.awaitCompleted()).isTrue();
  }
}
