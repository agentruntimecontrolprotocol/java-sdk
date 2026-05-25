package dev.arcp.examples.jakarta;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.WebSocketTransport;
import dev.arcp.core.messages.JobResult;
import dev.arcp.middleware.jakarta.ArcpJakartaAdapter;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Demonstrates the Jakarta WebSocket middleware: an embedded Jetty server hosts the ARCP adapter;
 * the client connects via {@link WebSocketTransport} over a real TCP socket.
 */
public final class Main {
  public static void main(String[] args) throws Exception {
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "jakarta-echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();

    ArcpJakartaAdapter adapter =
        ArcpJakartaAdapter.builder().runtime(runtime).path("/arcp").build();

    Server server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0);
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler("/");
    server.setHandler(context);
    JakartaWebSocketServletContainerInitializer.configure(
        context,
        (servletContext, container) -> container.addEndpoint(adapter.serverEndpointConfig()));

    server.start();
    try {
      int port = connector.getLocalPort();
      URI uri = URI.create("ws://127.0.0.1:" + port + "/arcp");
      WebSocketTransport transport = WebSocketTransport.connect(uri);
      try (ArcpClient client = ArcpClient.builder(transport).build()) {
        client.connect(Duration.ofSeconds(5));
        JobHandle handle =
            client.submit(
                ArcpClient.jobSubmit(
                    "jakarta-echo@1.0.0",
                    JsonNodeFactory.instance.objectNode().put("greeting", "jakarta")));
        JobResult result = handle.result().get(5, TimeUnit.SECONDS);
        assert "jakarta".equals(result.result().get("greeting").asText())
            : "unexpected result: " + result.result();
        System.out.println("OK jakarta");
      }
    } finally {
      server.stop();
      runtime.close();
    }
  }
}
