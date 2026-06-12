package dev.arcp.middleware.spring.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.middleware.spring.ArcpSpringBootAutoConfiguration;
import dev.arcp.middleware.spring.ArcpSpringBootProperties;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the registration branches of {@link ArcpSpringBootAutoConfiguration}: default allow-all
 * origins vs configured origins, and conditional host-allowlist interceptor wiring (#99).
 */
class SpringAutoConfigurationCoverageTest {

  private ArcpRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  void defaultsRegisterWildcardOriginsWithoutHostInterceptor() {
    ArcpSpringBootProperties properties = new ArcpSpringBootProperties();
    ArcpSpringBootAutoConfiguration configuration =
        new ArcpSpringBootAutoConfiguration(runtime, properties);
    SpringTestSupport.RegistryRecorder rec = new SpringTestSupport.RegistryRecorder();

    configuration.registerWebSocketHandlers(SpringTestSupport.registry(rec));

    assertThat(rec.handler).isNotNull();
    assertThat(rec.paths).containsExactly("/arcp");
    assertThat(rec.origins).containsExactly("*");
    assertThat(rec.interceptors).isEmpty();
  }

  @Test
  void configuredHostsAndOriginsAreEnforced() {
    ArcpSpringBootProperties properties = new ArcpSpringBootProperties();
    properties.setPath("/ws");
    properties.setAllowedOrigins(List.of("https://app.example.com"));
    properties.setAllowedHosts(List.of("agents.example.com"));
    ArcpSpringBootAutoConfiguration configuration =
        new ArcpSpringBootAutoConfiguration(runtime, properties);
    SpringTestSupport.RegistryRecorder rec = new SpringTestSupport.RegistryRecorder();

    configuration.registerWebSocketHandlers(SpringTestSupport.registry(rec));

    assertThat(rec.paths).containsExactly("/ws");
    assertThat(rec.origins).containsExactly("https://app.example.com");
    assertThat(rec.interceptors).hasSize(1);
    assertThat(rec.interceptors.get(0).getClass().getSimpleName())
        .isEqualTo("HostAllowlistHandshakeInterceptor");
  }

  @Test
  void propertiesRoundTrip() {
    ArcpSpringBootProperties properties = new ArcpSpringBootProperties();

    assertThat(properties.getPath()).isEqualTo("/arcp");
    assertThat(properties.getAllowedHosts()).isEmpty();
    assertThat(properties.getAllowedOrigins()).isEmpty();

    properties.setPath("/elsewhere");
    properties.setAllowedHosts(List.of("agents.example.com"));
    properties.setAllowedOrigins(List.of("https://app.example.com"));

    assertThat(properties.getPath()).isEqualTo("/elsewhere");
    assertThat(properties.getAllowedHosts()).containsExactly("agents.example.com");
    assertThat(properties.getAllowedOrigins()).containsExactly("https://app.example.com");
  }
}
