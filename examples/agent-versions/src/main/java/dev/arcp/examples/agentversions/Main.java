package dev.arcp.examples.agentversions;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.error.AgentVersionNotAvailableException;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Two registered versions; bare-name resolves to default; unknown version errors. */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("code-refactor", "1.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(
                                JsonNodeFactory.instance.objectNode().put("v", "1.0.0")))
                .agent("code-refactor", "2.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(
                                JsonNodeFactory.instance.objectNode().put("v", "2.0.0")))
                .build();
        runtime.agents().setDefault("code-refactor", "2.0.0");
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));

            JobHandle pinned = client.submit(ArcpClient.jobSubmit(
                    "code-refactor@1.0.0", JsonNodeFactory.instance.objectNode()));
            JobResult pinnedResult = pinned.result().get(5, TimeUnit.SECONDS);
            assert "1.0.0".equals(pinnedResult.result().get("v").asText());
            assert "code-refactor@1.0.0".equals(pinned.resolvedAgent());

            JobHandle bare = client.submit(ArcpClient.jobSubmit(
                    "code-refactor", JsonNodeFactory.instance.objectNode()));
            JobResult bareResult = bare.result().get(5, TimeUnit.SECONDS);
            assert "2.0.0".equals(bareResult.result().get("v").asText());
            assert "code-refactor@2.0.0".equals(bare.resolvedAgent());

            try {
                client.submit(ArcpClient.jobSubmit(
                        "code-refactor@9.9.9", JsonNodeFactory.instance.objectNode()));
                throw new AssertionError("expected AgentVersionNotAvailableException");
            } catch (RuntimeException e) {
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assert root instanceof AgentVersionNotAvailableException
                        : "got " + root;
            }

            System.out.println("OK agent-versions");
        }
        runtime.close();
    }
}
