package dev.arcp.examples.springboot;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.WebSocketTransport;
import dev.arcp.core.messages.JobResult;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Demonstrates the Spring Boot middleware: the {@link ArcpRuntime} bean triggers auto-configuration
 * that registers a WebSocket handler at {@code /arcp}; the client connects via a real TCP socket.
 */
@SpringBootApplication
public class Main {

  @Bean
  ArcpRuntime arcpRuntime() {
    return ArcpRuntime.builder()
        .agent("spring-echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
        .build();
  }

  public static void main(String[] args) throws Exception {
    ConfigurableApplicationContext ctx = SpringApplication.run(Main.class, "--server.port=0");
    try {
      int port = ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
      URI uri = URI.create("ws://127.0.0.1:" + port + "/arcp");
      WebSocketTransport transport = WebSocketTransport.connect(uri);
      try (ArcpClient client = ArcpClient.builder(transport).build()) {
        client.connect(Duration.ofSeconds(5));
        JobHandle handle =
            client.submit(
                ArcpClient.jobSubmit(
                    "spring-echo@1.0.0",
                    JsonNodeFactory.instance.objectNode().put("greeting", "spring")));
        JobResult result = handle.result().get(5, TimeUnit.SECONDS);
        assert "spring".equals(result.result().get("greeting").asText())
            : "unexpected result: " + result.result();
        System.out.println("OK spring-boot");
      }
    } finally {
      ctx.close();
    }
  }
}
