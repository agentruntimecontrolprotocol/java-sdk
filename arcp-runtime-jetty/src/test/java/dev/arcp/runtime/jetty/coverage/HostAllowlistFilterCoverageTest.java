package dev.arcp.runtime.jetty.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers the §14/#99 branches of the package-private {@code HostAllowlistFilter}: allow-all on
 * empty allowlist, exact-match allow, deny with 403 (including null Host), exact (no
 * port-stripping, case-sensitive) matching, and the non-HTTP passthrough guard.
 */
class HostAllowlistFilterCoverageTest {

  private static Filter filter(List<String> allowedHosts) throws Exception {
    Class<?> cls = Class.forName("dev.arcp.runtime.jetty.HostAllowlistFilter");
    Constructor<?> ctor = cls.getDeclaredConstructor(List.class);
    ctor.setAccessible(true);
    return (Filter) ctor.newInstance(allowedHosts);
  }

  static Stream<Arguments> hostDecisions() {
    return Stream.of(
        // Empty allowlist disables the check (allow-all), with or without a Host header.
        Arguments.of(List.of(), "agents.example.com", true),
        Arguments.of(List.of(), null, true),
        // Exact match passes; later entries match too.
        Arguments.of(List.of("agents.example.com"), "agents.example.com", true),
        Arguments.of(List.of("a.example", "b.example"), "b.example", true),
        // Disallowed host gets 403 before the upgrade; so does a missing Host header.
        Arguments.of(List.of("agents.example.com"), "evil.example.com", false),
        Arguments.of(List.of("agents.example.com"), null, false),
        // Matching is exact: case differences are refused.
        Arguments.of(List.of("agents.example.com"), "AGENTS.EXAMPLE.COM", false),
        // Matching is exact: ports are not stripped before comparison.
        Arguments.of(List.of("agents.example.com"), "agents.example.com:8443", false),
        Arguments.of(List.of("agents.example.com:8443"), "agents.example.com:8443", true));
  }

  @ParameterizedTest
  @MethodSource("hostDecisions")
  void hostAllowlistReturns403BeforeUpgrade(
      List<String> allowedHosts, String hostHeader, boolean expectAllowed) throws Exception {
    List<String> errors = new ArrayList<>();
    AtomicBoolean chained = new AtomicBoolean();
    FilterChain chain = (request, response) -> chained.set(true);

    filter(allowedHosts)
        .doFilter(
            JettyTestSupport.httpRequest(hostHeader), JettyTestSupport.httpResponse(errors), chain);

    if (expectAllowed) {
      assertThat(chained).isTrue();
      assertThat(errors).isEmpty();
    } else {
      assertThat(chained).isFalse();
      assertThat(errors).containsExactly("403:host not allowed");
    }
  }

  @Test
  void nonHttpRequestPassesThroughUnchecked() throws Exception {
    AtomicBoolean chained = new AtomicBoolean();
    FilterChain chain = (request, response) -> chained.set(true);

    filter(List.of("agents.example.com"))
        .doFilter(JettyTestSupport.plainRequest(), JettyTestSupport.plainResponse(), chain);

    assertThat(chained).isTrue();
  }

  @Test
  void nonHttpResponsePassesThroughUnchecked() throws Exception {
    AtomicBoolean chained = new AtomicBoolean();
    FilterChain chain = (request, response) -> chained.set(true);

    filter(List.of("agents.example.com"))
        .doFilter(
            JettyTestSupport.httpRequest("evil.example.com"),
            JettyTestSupport.plainResponse(),
            chain);

    assertThat(chained).isTrue();
  }
}
