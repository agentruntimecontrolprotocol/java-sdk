package dev.arcp.middleware.jakarta.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.middleware.jakarta.ArcpJakartaAdapter;
import dev.arcp.middleware.jakarta.ArcpJakartaEndpoint;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import jakarta.websocket.CloseReason;
import jakarta.websocket.server.ServerEndpointConfig;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the per-handshake host-allowlist decision recording in the anonymous {@link
 * ServerEndpointConfig.Configurator} built by {@link ArcpJakartaAdapter} and the resulting
 * VIOLATED_POLICY close performed by {@link ArcpJakartaEndpoint} (#100).
 */
class JakartaHostAllowlistConfiguratorTest {

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

  static Stream<Arguments> hostDecisions() {
    return Stream.of(
        // Empty allowlist disables the check entirely (allow-all).
        Arguments.of(List.of(), Map.of("Host", List.of("anything.example")), false),
        Arguments.of(List.of(), Map.of(), false),
        // Exact match is allowed.
        Arguments.of(
            List.of("agents.example.com"), Map.of("Host", List.of("agents.example.com")), false),
        // Second entry of a multi-host allowlist matches too.
        Arguments.of(
            List.of("a.example", "b.example"), Map.of("Host", List.of("b.example")), false),
        // Non-allowlisted host is rejected.
        Arguments.of(
            List.of("agents.example.com"), Map.of("Host", List.of("evil.example.com")), true),
        // Missing Host header is rejected when an allowlist is configured.
        Arguments.of(List.of("agents.example.com"), Map.of(), true),
        // Present-but-empty Host header list is rejected.
        Arguments.of(List.of("agents.example.com"), Map.of("Host", List.of()), true),
        // Matching is exact: case differences are rejected.
        Arguments.of(
            List.of("agents.example.com"), Map.of("Host", List.of("AGENTS.EXAMPLE.COM")), true),
        // Matching is exact: a port suffix is not stripped.
        Arguments.of(
            List.of("agents.example.com"),
            Map.of("Host", List.of("agents.example.com:8443")),
            true),
        // ...but an allowlist entry that includes the port matches exactly.
        Arguments.of(
            List.of("agents.example.com:8443"),
            Map.of("Host", List.of("agents.example.com:8443")),
            false));
  }

  @ParameterizedTest
  @MethodSource("hostDecisions")
  void handshakeHostDecisionDrivesEndpointPolicyClose(
      List<String> allowlist, Map<String, List<String>> headers, boolean expectRejected)
      throws Exception {
    ArcpJakartaAdapter adapter =
        ArcpJakartaAdapter.builder().runtime(runtime).path("/arcp").allowedHosts(allowlist).build();
    ServerEndpointConfig config = adapter.serverEndpointConfig();
    ServerEndpointConfig.Configurator configurator = config.getConfigurator();

    configurator.modifyHandshake(
        config,
        JakartaTestSupport.handshakeRequest(headers),
        JakartaTestSupport.handshakeResponse());
    ArcpJakartaEndpoint endpoint = configurator.getEndpointInstance(ArcpJakartaEndpoint.class);

    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    endpoint.onOpen(JakartaTestSupport.session("matrix", rec), config);

    if (expectRejected) {
      assertThat(rec.closeReasons).hasSize(1);
      assertThat(rec.closeReasons.get(0).getCloseCode())
          .isEqualTo(CloseReason.CloseCodes.VIOLATED_POLICY);
      assertThat(rec.textHandler.get()).as("runtime must never see a rejected session").isNull();
    } else {
      assertThat(rec.closeReasons).isEmpty();
      assertThat(rec.textHandler.get()).as("accepted session reaches the runtime").isNotNull();
      endpoint.onClose(
          JakartaTestSupport.session("matrix", rec),
          new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "done"));
    }
  }

  @Test
  void rejectionDecisionIsConsumedPerHandshake() throws Exception {
    ArcpJakartaAdapter adapter =
        ArcpJakartaAdapter.builder()
            .runtime(runtime)
            .allowedHosts(List.of("agents.example.com"))
            .build();
    ServerEndpointConfig config = adapter.serverEndpointConfig();
    ServerEndpointConfig.Configurator configurator = config.getConfigurator();

    configurator.modifyHandshake(
        config,
        JakartaTestSupport.handshakeRequest(Map.of()),
        JakartaTestSupport.handshakeResponse());
    ArcpJakartaEndpoint rejected = configurator.getEndpointInstance(ArcpJakartaEndpoint.class);
    // The ThreadLocal decision is removed by getEndpointInstance: a follow-up instance created on
    // the same thread without a new handshake falls back to "not rejected".
    ArcpJakartaEndpoint fresh = configurator.getEndpointInstance(ArcpJakartaEndpoint.class);

    JakartaTestSupport.SessionRecorder rejectedRec = new JakartaTestSupport.SessionRecorder();
    rejected.onOpen(JakartaTestSupport.session("rejected", rejectedRec), config);
    assertThat(rejectedRec.closeReasons).hasSize(1);
    assertThat(rejectedRec.closeReasons.get(0).getCloseCode())
        .isEqualTo(CloseReason.CloseCodes.VIOLATED_POLICY);

    JakartaTestSupport.SessionRecorder freshRec = new JakartaTestSupport.SessionRecorder();
    fresh.onOpen(JakartaTestSupport.session("fresh", freshRec), config);
    assertThat(freshRec.closeReasons).isEmpty();
    fresh.onClose(
        JakartaTestSupport.session("fresh", freshRec),
        new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "done"));
  }

  @Test
  void violatedPolicyCloseFailureIsSwallowed() throws Exception {
    ArcpJakartaAdapter adapter =
        ArcpJakartaAdapter.builder()
            .runtime(runtime)
            .allowedHosts(List.of("agents.example.com"))
            .build();
    ServerEndpointConfig config = adapter.serverEndpointConfig();
    ServerEndpointConfig.Configurator configurator = config.getConfigurator();

    configurator.modifyHandshake(
        config,
        JakartaTestSupport.handshakeRequest(Map.of("Host", List.of("evil.example.com"))),
        JakartaTestSupport.handshakeResponse());
    ArcpJakartaEndpoint endpoint = configurator.getEndpointInstance(ArcpJakartaEndpoint.class);

    JakartaTestSupport.SessionRecorder rec = new JakartaTestSupport.SessionRecorder();
    // close() throws IOException; onOpen must treat the close as best-effort and not propagate.
    endpoint.onOpen(JakartaTestSupport.session("throwing", rec, true, false), config);
    assertThat(rec.closeReasons).isEmpty();
    assertThat(rec.textHandler.get()).isNull();
  }

  @Test
  void checkOriginIsAllowAllWhenNoOriginsConfigured() {
    ArcpJakartaAdapter adapter = ArcpJakartaAdapter.builder().runtime(runtime).build();
    ServerEndpointConfig.Configurator configurator =
        adapter.serverEndpointConfig().getConfigurator();

    assertThat(configurator.checkOrigin("https://anywhere.example")).isTrue();
    assertThat(configurator.checkOrigin(null)).isTrue();
  }

  @Test
  void checkOriginEnforcesConfiguredOrigins() {
    ArcpJakartaAdapter adapter =
        ArcpJakartaAdapter.builder()
            .runtime(runtime)
            .allowedOrigins(List.of("https://app.example.com"))
            .build();
    ServerEndpointConfig.Configurator configurator =
        adapter.serverEndpointConfig().getConfigurator();

    assertThat(configurator.checkOrigin("https://app.example.com")).isTrue();
    assertThat(configurator.checkOrigin("https://evil.example.com")).isFalse();
    assertThat(configurator.checkOrigin(null)).isFalse();
  }

  @Test
  void builderExposesConfiguredState() {
    ArcpJakartaAdapter adapter =
        ArcpJakartaAdapter.builder()
            .runtime(runtime)
            .path("/custom")
            .allowedHosts(List.of("agents.example.com"))
            .allowedOrigins(List.of("https://app.example.com"))
            .build();

    assertThat(adapter.runtime()).isSameAs(runtime);
    assertThat(adapter.path()).isEqualTo("/custom");
    assertThat(adapter.allowedHosts()).containsExactly("agents.example.com");
    assertThat(adapter.allowedOrigins()).containsExactly("https://app.example.com");
  }
}
