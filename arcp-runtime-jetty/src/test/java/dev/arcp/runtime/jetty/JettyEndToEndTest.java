package dev.arcp.runtime.jetty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.Session;
import dev.arcp.client.WebSocketTransport;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.messages.JobResult;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JettyEndToEndTest {

    @Test
    void clientConnectsAndRunsJobOverWebSocket() throws Exception {
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("echo", "1.0.0", (input, ctx) -> {
                    ctx.emit(new LogEvent("info", "echoing"));
                    return JobOutcome.Success.inline(input.payload());
                })
                .build();
        try (ArcpJettyServer server = ArcpJettyServer.builder(runtime).build().start()) {
            WebSocketTransport transport = WebSocketTransport.connect(server.uri());
            try (ArcpClient client = ArcpClient.builder(transport).build()) {
                Session session = client.connect(Duration.ofSeconds(5));
                assertThat(session.sessionId()).isNotNull();

                ObjectNode input = JsonNodeFactory.instance.objectNode();
                input.put("ping", "pong");
                JobHandle handle = client.submit(ArcpClient.jobSubmit("echo@1.0.0", input));
                JobResult result = handle.result().get(5, TimeUnit.SECONDS);
                assertThat(result.finalStatus()).isEqualTo(JobResult.SUCCESS);
                assertThat(result.result().get("ping").asText()).isEqualTo("pong");
            }
        }
        runtime.close();
    }
}
