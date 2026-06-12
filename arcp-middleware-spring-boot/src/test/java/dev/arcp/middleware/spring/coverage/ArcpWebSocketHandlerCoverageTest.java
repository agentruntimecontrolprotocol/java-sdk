package dev.arcp.middleware.spring.coverage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.middleware.spring.ArcpWebSocketHandler;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Covers {@link ArcpWebSocketHandler} text/close/error handling for both tracked and untracked
 * sessions.
 */
class ArcpWebSocketHandlerCoverageTest {

  private ArcpRuntime runtime;
  private ArcpWebSocketHandler handler;

  @BeforeEach
  void setUp() {
    runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
    handler = new ArcpWebSocketHandler(runtime);
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private static WebSocketSession session(String id) {
    return SpringTestSupport.webSocketSession(id, new SpringTestSupport.WebSessionRecorder());
  }

  @Test
  void nullRuntimeIsRejected() {
    assertThatThrownBy(() -> new ArcpWebSocketHandler(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void textMessagesAreDeliveredToTheTrackedTransport() throws Exception {
    WebSocketSession session = session("text-1");
    handler.afterConnectionEstablished(session);

    // Well-formed and malformed frames both go through the established transport.
    handler.handleMessage(session, new TextMessage(SpringTestSupport.pingFrame()));
    handler.handleMessage(session, new TextMessage("not an envelope"));

    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
  }

  @Test
  void textMessageForUnknownSessionIsIgnored() {
    assertThatCode(() -> handler.handleMessage(session("never-established"), new TextMessage("{}")))
        .doesNotThrowAnyException();
  }

  @Test
  void closeCompletesInboundOnceAndIgnoresRepeats() throws Exception {
    WebSocketSession session = session("close-1");
    handler.afterConnectionEstablished(session);

    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    // Second close for the same id hits the already-removed branch.
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
  }

  @Test
  void transportErrorFailsInboundOnceAndIgnoresRepeats() throws Exception {
    WebSocketSession session = session("error-1");
    handler.afterConnectionEstablished(session);

    handler.handleTransportError(session, new RuntimeException("boom"));
    // Transport was removed by the first error; the repeat sees no tracked transport.
    handler.handleTransportError(session, new RuntimeException("boom again"));
    handler.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
  }
}
