package dev.arcp.runtime.jetty.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.jetty.ArcpJettyServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end §14/#99 check: a server built with a host allowlist installs the filter and refuses a
 * disallowed Host with 403 before any WebSocket upgrade can run.
 */
class JettyServerHostAllowlistTest {

  @Test
  void disallowedHostIsRefusedWith403BeforeUpgrade() throws Exception {
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
    try (ArcpJettyServer server =
        ArcpJettyServer.builder(runtime)
            .path("/arcp")
            .port(0)
            .allowedHosts(List.of("agents.example.com"))
            .build()
            .start()) {
      int port = server.port();
      assertThat(server.uri().getPort()).isEqualTo(port);

      assertThat(rawHttpStatus(port, "evil.example.com")).isEqualTo(403);
      assertThat(rawHttpStatus(port, "agents.example.com:9999")).isEqualTo(403);
      // An allowlisted Host clears the filter; the request then fails the upgrade (no WebSocket
      // headers) but is not refused by the host allowlist.
      assertThat(rawHttpStatus(port, "agents.example.com")).isNotEqualTo(403);
    } finally {
      runtime.close();
    }
  }

  private static int rawHttpStatus(int port, String hostHeader) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET /arcp HTTP/1.1\r\nHost: " + hostHeader + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
        String statusLine = reader.readLine();
        assertThat(statusLine).as("HTTP status line").isNotNull().startsWith("HTTP/1.1 ");
        return Integer.parseInt(statusLine.split(" ")[1]);
      }
    }
  }
}
