package dev.arcp.middleware.jakarta;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

class JakartaAdapterEndToEndTest {

    @Test
    void adapterAcceptsAndExecutesJob() throws Exception {
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("jakarta-echo", "1.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                .build();

        ArcpJakartaAdapter adapter = ArcpJakartaAdapter.builder()
                .runtime(runtime)
                .path("/arcp")
                .build();

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler("/");
        server.setHandler(context);
        JakartaWebSocketServletContainerInitializer.configure(
                context,
                (servletContext, container) ->
                        container.addEndpoint(adapter.serverEndpointConfig()));

        server.start();
        try {
            int port = connector.getLocalPort();
            URI uri = URI.create("ws://127.0.0.1:" + port + "/arcp");
            WebSocketTransport transport = WebSocketTransport.connect(uri);
            try (ArcpClient client = ArcpClient.builder(transport).build()) {
                client.connect(Duration.ofSeconds(5));
                JobHandle handle = client.submit(ArcpClient.jobSubmit(
                        "jakarta-echo@1.0.0",
                        JsonNodeFactory.instance.objectNode().put("greeting", "jakarta")));
                JobResult result = handle.result().get(5, TimeUnit.SECONDS);
                assertThat(result.finalStatus()).isEqualTo(JobResult.SUCCESS);
                assertThat(result.result().get("greeting").asText()).isEqualTo("jakarta");
            }
        } finally {
            server.stop();
            runtime.close();
        }
    }
}
