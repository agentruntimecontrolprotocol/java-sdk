package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.wire.ArcpMapper;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * #113: a server that accepts the TCP connection but never completes the HTTP upgrade must not turn
 * the configured connect timeout into an indefinite hang — the JDK client retries on EOF, and
 * {@code HttpClient.close()} waits for the abandoned handshake.
 */
class WebSocketConnectTimeoutTest {

  @Test
  @Timeout(15)
  void connectThrowsPromptlyAgainstSilentServer() throws Exception {
    AtomicBoolean running = new AtomicBoolean(true);
    try (ServerSocket silent = new ServerSocket(0)) {
      Thread accepter =
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (running.get()) {
                      try {
                        Socket s = silent.accept();
                        // Read and discard the upgrade request; never respond.
                        s.getInputStream().read(new byte[4096]);
                      } catch (java.io.IOException e) {
                        return;
                      }
                    }
                  });
      URI uri = URI.create("ws://127.0.0.1:" + silent.getLocalPort() + "/arcp");
      long start = System.nanoTime();
      assertThatThrownBy(
              () ->
                  WebSocketTransport.connect(
                      uri, Map.of(), ArcpMapper.shared(), Duration.ofMillis(500)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("timed out");
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
      // The hang fixed by #113 was unbounded; allow generous slack for slow CI but require the
      // call to return in the same order of magnitude as the configured 500ms timeout.
      assertThat(elapsedMillis).isLessThan(10_000);
      running.set(false);
      accepter.interrupt();
    }
  }
}
