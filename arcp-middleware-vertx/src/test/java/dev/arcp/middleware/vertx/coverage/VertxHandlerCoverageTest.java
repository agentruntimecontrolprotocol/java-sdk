package dev.arcp.middleware.vertx.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.middleware.vertx.ArcpVertxHandler;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers {@link ArcpVertxHandler} handshake accept/reject branches (path + host allowlist) and
 * close/error propagation into the transport.
 */
class VertxHandlerCoverageTest {

  private static ArcpRuntime runtime;

  @BeforeAll
  static void startRuntime() {
    runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
  }

  @AfterAll
  static void stopRuntime() {
    runtime.close();
  }

  @Test
  void pathMismatchIsClosedWithPolicyViolation() {
    ArcpVertxHandler handler = ArcpVertxHandler.builder().runtime(runtime).path("/arcp").build();
    VertxTestSupport.WsRecorder rec = new VertxTestSupport.WsRecorder();
    rec.path = "/elsewhere";

    handler.handle(VertxTestSupport.serverWebSocket(rec));

    assertThat(rec.closeReasons).containsExactly("1008:path mismatch");
    assertThat(rec.textHandler.get()).isNull();
  }

  static Stream<Arguments> hostDecisions() {
    return Stream.of(
        // Empty allowlist disables the check (allow-all), with or without a Host header.
        Arguments.of(List.of(), "agents.example.com", true),
        Arguments.of(List.of(), null, true),
        // Exact match passes; later entries match too.
        Arguments.of(List.of("agents.example.com"), "agents.example.com", true),
        Arguments.of(List.of("a.example", "b.example"), "b.example", true),
        // Disallowed or missing host is refused before the runtime sees the socket.
        Arguments.of(List.of("agents.example.com"), "evil.example.com", false),
        Arguments.of(List.of("agents.example.com"), null, false),
        // Matching is exact: case differences and port suffixes are refused.
        Arguments.of(List.of("agents.example.com"), "AGENTS.EXAMPLE.COM", false),
        Arguments.of(List.of("agents.example.com"), "agents.example.com:8443", false),
        Arguments.of(List.of("agents.example.com:8443"), "agents.example.com:8443", true));
  }

  @ParameterizedTest
  @MethodSource("hostDecisions")
  void hostAllowlistGatesTheHandshake(
      List<String> allowedHosts, String hostHeader, boolean expectAccepted) {
    ArcpVertxHandler handler =
        ArcpVertxHandler.builder().runtime(runtime).allowedHosts(allowedHosts).build();
    VertxTestSupport.WsRecorder rec = new VertxTestSupport.WsRecorder();
    if (hostHeader != null) {
      rec.headers.set("Host", hostHeader);
    }

    handler.handle(VertxTestSupport.serverWebSocket(rec));

    if (expectAccepted) {
      assertThat(rec.closeReasons).isEmpty();
      assertThat(rec.textHandler.get()).as("accepted socket reaches the runtime").isNotNull();
      assertThat(rec.closeHandler.get()).isNotNull();
      assertThat(rec.exceptionHandler.get()).isNotNull();
      rec.closeHandler.get().handle(null);
    } else {
      assertThat(rec.closeReasons).containsExactly("1008:host not allowed");
      assertThat(rec.textHandler.get()).as("rejected socket never reaches the runtime").isNull();
    }
  }

  @Test
  void acceptedSocketDeliversFramesAndPropagatesCloseAndErrors() throws Exception {
    ArcpVertxHandler handler = ArcpVertxHandler.builder().runtime(runtime).build();
    VertxTestSupport.WsRecorder rec = new VertxTestSupport.WsRecorder();

    handler.handle(VertxTestSupport.serverWebSocket(rec));
    assertThat(rec.textHandler.get()).isNotNull();

    // Decode + dispatch: well-formed and malformed frames both flow through the transport.
    rec.textHandler.get().handle(VertxTestSupport.pingFrame());
    rec.textHandler.get().handle("not an envelope");

    // Error propagation fails the inbound stream; close propagation completes it.
    rec.exceptionHandler.get().handle(new RuntimeException("boom"));

    VertxTestSupport.WsRecorder second = new VertxTestSupport.WsRecorder();
    handler.handle(VertxTestSupport.serverWebSocket(second));
    second.closeHandler.get().handle(null);
  }

  @Test
  void builderExposesConfiguredState() {
    ArcpVertxHandler handler =
        ArcpVertxHandler.builder()
            .runtime(runtime)
            .path("/custom")
            .allowedHosts(List.of("agents.example.com"))
            .build();

    assertThat(handler.runtime()).isSameAs(runtime);
    assertThat(handler.path()).isEqualTo("/custom");
    assertThat(handler.allowedHosts()).containsExactly("agents.example.com");
  }
}
