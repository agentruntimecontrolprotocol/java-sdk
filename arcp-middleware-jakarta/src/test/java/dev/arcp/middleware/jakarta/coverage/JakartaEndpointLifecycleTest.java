package dev.arcp.middleware.jakarta.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.middleware.jakarta.ArcpJakartaAdapter;
import dev.arcp.middleware.jakarta.ArcpJakartaEndpoint;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import jakarta.websocket.CloseReason;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ArcpJakartaEndpoint} message decode/dispatch wiring and the close/error callbacks,
 * including the missing-runtime guard.
 */
class JakartaEndpointLifecycleTest {

  private ArcpRuntime runtime;

  @BeforeEach
  void startRuntime() {
    runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
  }

  @AfterEach
  void stopRuntime() {
    runtime.close();
  }

  private ServerEndpointConfig configWithRuntime() {
    return ArcpJakartaAdapter.builder()
        .runtime(runtime)
        .path("/arcp")
        .build()
        .serverEndpointConfig();
  }

  @Test
  void missingRuntimeClosesWithUnexpectedCondition() {
    ServerEndpointConfig bare =
        ServerEndpointConfig.Builder.create(ArcpJakartaEndpoint.class, "/arcp").build();
    ArcpJakartaEndpoint endpoint = new ArcpJakartaEndpoint();

    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    endpoint.onOpen(JakartaTestSupport.session("no-runtime", rec), bare);

    assertThat(rec.closeReasons).hasSize(1);
    assertThat(rec.closeReasons.get(0).getCloseCode())
        .isEqualTo(CloseReason.CloseCodes.UNEXPECTED_CONDITION);
    assertThat(rec.textHandler.get()).isNull();
  }

  @Test
  void missingRuntimeCloseFailureIsSwallowed() {
    ServerEndpointConfig bare =
        ServerEndpointConfig.Builder.create(ArcpJakartaEndpoint.class, "/arcp").build();
    ArcpJakartaEndpoint endpoint = new ArcpJakartaEndpoint();

    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    endpoint.onOpen(JakartaTestSupport.session("no-runtime-throwing", rec, true, false), bare);

    assertThat(rec.closeReasons).isEmpty();
    assertThat(rec.textHandler.get()).isNull();
  }

  @Test
  void openDeliverCloseLifecycle() throws Exception {
    ServerEndpointConfig config = configWithRuntime();
    ArcpJakartaEndpoint endpoint = new ArcpJakartaEndpoint();
    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();

    endpoint.onOpen(JakartaTestSupport.session("lifecycle", rec), config);
    assertThat(rec.textHandler.get()).isNotNull();

    // Decode + dispatch: a well-formed envelope frame reaches the transport...
    rec.textHandler.get().onMessage(JakartaTestSupport.pingFrame());
    // ...and a malformed frame is dropped without breaking the session.
    rec.textHandler.get().onMessage("this is not an envelope");

    // Close for a tracked session completes the inbound stream; a second close for the same id
    // hits the already-removed branch.
    CloseReason normal = new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "bye");
    endpoint.onClose(JakartaTestSupport.session("lifecycle", rec), normal);
    endpoint.onClose(JakartaTestSupport.session("lifecycle", rec), normal);
    endpoint.onError(JakartaTestSupport.session("lifecycle", rec), new RuntimeException("late"));
  }

  @Test
  void errorCallbackFailsInboundForTrackedSession() {
    ServerEndpointConfig config = configWithRuntime();
    ArcpJakartaEndpoint endpoint = new ArcpJakartaEndpoint();
    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();

    endpoint.onOpen(JakartaTestSupport.session("erroring", rec), config);
    assertThat(rec.textHandler.get()).isNotNull();

    endpoint.onError(JakartaTestSupport.session("erroring", rec), new RuntimeException("boom"));
    // The transport was removed by onError; the close callback now sees no tracked transport.
    endpoint.onClose(
        JakartaTestSupport.session("erroring", rec),
        new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, "gone"));
  }
}
