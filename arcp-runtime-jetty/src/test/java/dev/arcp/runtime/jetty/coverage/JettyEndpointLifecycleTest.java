package dev.arcp.runtime.jetty.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.jetty.ArcpJettyEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ArcpJettyEndpoint} open/close/error callbacks, including the missing-runtime guard
 * and the already-removed-transport branches.
 */
class JettyEndpointLifecycleTest {

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
    ServerEndpointConfig config =
        ServerEndpointConfig.Builder.create(ArcpJettyEndpoint.class, "/arcp").build();
    config.getUserProperties().put(ArcpJettyEndpoint.RUNTIME_KEY, runtime);
    return config;
  }

  @Test
  void missingRuntimeClosesWithUnexpectedCondition() {
    ServerEndpointConfig bare =
        ServerEndpointConfig.Builder.create(ArcpJettyEndpoint.class, "/arcp").build();
    ArcpJettyEndpoint endpoint = new ArcpJettyEndpoint();

    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();
    endpoint.onOpen(JettyTestSupport.session("no-runtime", rec), bare);

    assertThat(rec.closeReasons).hasSize(1);
    assertThat(rec.closeReasons.get(0).getCloseCode())
        .isEqualTo(CloseReason.CloseCodes.UNEXPECTED_CONDITION);
    assertThat(rec.textHandler.get()).isNull();
  }

  @Test
  void missingRuntimeCloseFailureIsSwallowed() {
    ServerEndpointConfig bare =
        ServerEndpointConfig.Builder.create(ArcpJettyEndpoint.class, "/arcp").build();
    ArcpJettyEndpoint endpoint = new ArcpJettyEndpoint();

    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();
    endpoint.onOpen(JettyTestSupport.session("no-runtime-throwing", rec, true, false), bare);

    assertThat(rec.closeReasons).isEmpty();
    assertThat(rec.textHandler.get()).isNull();
  }

  @Test
  void openDeliverCloseLifecycle() throws Exception {
    ServerEndpointConfig config = configWithRuntime();
    ArcpJettyEndpoint endpoint = new ArcpJettyEndpoint();
    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();

    endpoint.onOpen(JettyTestSupport.session("lifecycle", rec), config);
    assertThat(rec.textHandler.get()).isNotNull();

    // Decode + dispatch: a well-formed envelope frame reaches the transport, a malformed one is
    // logged and dropped.
    rec.textHandler.get().onMessage(JettyTestSupport.pingFrame());
    rec.textHandler.get().onMessage("this is not an envelope");

    CloseReason normal = new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "bye");
    endpoint.onClose(JettyTestSupport.session("lifecycle", rec), normal);
    // Second close and a late error both hit the already-removed branches.
    endpoint.onClose(JettyTestSupport.session("lifecycle", rec), normal);
    endpoint.onError(JettyTestSupport.session("lifecycle", rec), new RuntimeException("late"));
  }

  @Test
  void errorCallbackFailsInboundForTrackedSession() {
    ServerEndpointConfig config = configWithRuntime();
    ArcpJettyEndpoint endpoint = new ArcpJettyEndpoint();
    JettyTestSupport.SessionRecorder rec = new JettyTestSupport.SessionRecorder();

    endpoint.onOpen(JettyTestSupport.session("erroring", rec), config);
    assertThat(rec.textHandler.get()).isNotNull();

    endpoint.onError(JettyTestSupport.session("erroring", rec), new RuntimeException("boom"));
    endpoint.onClose(
        JettyTestSupport.session("erroring", rec),
        new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, "gone"));
  }
}
