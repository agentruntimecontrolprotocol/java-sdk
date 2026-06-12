package dev.arcp.middleware.spring.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Covers the allowed / denied / missing-host / no-allowlist branches of the package-private {@code
 * HostAllowlistHandshakeInterceptor} (§14, #99).
 */
class HostAllowlistInterceptorCoverageTest {

  private static HandshakeInterceptor interceptor(List<String> allowedHosts) throws Exception {
    Class<?> cls = Class.forName("dev.arcp.middleware.spring.HostAllowlistHandshakeInterceptor");
    Constructor<?> ctor = cls.getDeclaredConstructor(List.class);
    ctor.setAccessible(true);
    return (HandshakeInterceptor) ctor.newInstance(allowedHosts);
  }

  static Stream<Arguments> hostDecisions() {
    return Stream.of(
        // Empty allowlist disables the check (allow-all), with or without a Host header.
        Arguments.of(List.of(), "agents.example.com", true),
        Arguments.of(List.of(), null, true),
        // Exact match passes; any other entry of the list works too.
        Arguments.of(List.of("agents.example.com"), "agents.example.com", true),
        Arguments.of(List.of("a.example", "b.example"), "b.example", true),
        // Disallowed host is refused with 403.
        Arguments.of(List.of("agents.example.com"), "evil.example.com", false),
        // Missing Host header is refused when an allowlist is configured.
        Arguments.of(List.of("agents.example.com"), null, false),
        // Matching is exact: case differences are refused.
        Arguments.of(List.of("agents.example.com"), "AGENTS.EXAMPLE.COM", false),
        // Matching is exact: ports are not stripped before comparison.
        Arguments.of(List.of("agents.example.com"), "agents.example.com:8443", false),
        Arguments.of(List.of("agents.example.com:8443"), "agents.example.com:8443", true));
  }

  @ParameterizedTest
  @MethodSource("hostDecisions")
  void beforeHandshakeEnforcesHostAllowlist(
      List<String> allowedHosts, String hostHeader, boolean expectAllowed) throws Exception {
    MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/arcp");
    if (hostHeader != null) {
      servletRequest.addHeader("Host", hostHeader);
    }
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();

    boolean allowed =
        interceptor(allowedHosts)
            .beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse),
                null,
                new HashMap<>());

    assertThat(allowed).isEqualTo(expectAllowed);
    if (!expectAllowed) {
      assertThat(servletResponse.getStatus()).isEqualTo(403);
    } else {
      assertThat(servletResponse.getStatus()).isNotEqualTo(403);
    }
  }

  @Test
  void afterHandshakeIsANoOp() throws Exception {
    MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/arcp");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();

    interceptor(List.of("agents.example.com"))
        .afterHandshake(
            new ServletServerHttpRequest(servletRequest),
            new ServletServerHttpResponse(servletResponse),
            null,
            null);

    assertThat(servletResponse.getStatus()).isEqualTo(200);
  }
}
