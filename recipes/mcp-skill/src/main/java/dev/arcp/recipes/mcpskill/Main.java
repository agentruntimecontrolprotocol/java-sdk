package dev.arcp.recipes.mcpskill;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.ThoughtEvent;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Recipe: Exposing an MCP tool call as an ARCP skill.
 *
 * <p>The agent authorizes the {@code mcp:weather} tool call, emits a {@link ThoughtEvent}
 * describing the invocation, and returns a simulated weather payload. The client's lease
 * explicitly permits only this one tool, demonstrating fine-grained capability scoping.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "weather-skill",
                                "1.0.0",
                                (input, ctx) -> {
                                    ctx.authorize("tool.call", "mcp:weather");
                                    ctx.emit(new ThoughtEvent("invoking mcp:weather tool"));
                                    // Simulate the MCP tool result.
                                    return JobOutcome.Success.inline(
                                            JsonNodeFactory.instance
                                                    .objectNode()
                                                    .put("location", "San Francisco")
                                                    .put("condition", "sunny")
                                                    .put("tempC", 22));
                                })
                        .build();
        runtime.accept(pair[0]);

        CompletableFuture<ThoughtEvent> thoughtReceived = new CompletableFuture<>();

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));

            // Lease allows only the mcp:weather tool call.
            Lease lease = Lease.builder().allow("tool.call", "mcp:weather").build();

            JobHandle handle =
                    client.submit(
                            ArcpClient.jobSubmit(
                                    "weather-skill@1.0.0",
                                    JsonNodeFactory.instance
                                            .objectNode()
                                            .put("location", "San Francisco"),
                                    lease,
                                    null,
                                    null,
                                    null));

            handle.events()
                    .subscribe(
                            new Flow.Subscriber<>() {
                                @Override
                                public void onSubscribe(Flow.Subscription s) {
                                    s.request(Long.MAX_VALUE);
                                }

                                @Override
                                public void onNext(EventBody body) {
                                    if (body instanceof ThoughtEvent te) {
                                        thoughtReceived.complete(te);
                                    }
                                }

                                @Override
                                public void onError(Throwable t) {
                                    thoughtReceived.completeExceptionally(t);
                                }

                                @Override
                                public void onComplete() {}
                            });

            JobResult result = handle.result().get(5, TimeUnit.SECONDS);
            ThoughtEvent te = thoughtReceived.get(5, TimeUnit.SECONDS);
            assert te.text().contains("mcp:weather") : "unexpected thought: " + te.text();
            assert "sunny".equals(result.result().get("condition").asText())
                    : "unexpected result: " + result.result();
            System.out.println("OK mcp-skill");
        }
        runtime.close();
    }
}
