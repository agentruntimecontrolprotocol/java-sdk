package dev.arcp.examples.resume;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.Session;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates session resume: after a first connection completes a job, the resume token and last
 * seen sequence are captured; a second client reconnects using those values and submits another job.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "echo",
                                "1.0.0",
                                (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                        .build();

        // First connection: submit a job and capture resume state.
        MemoryTransport.Pair pair1 = MemoryTransport.pair();
        runtime.accept(pair1.runtime());

        String resumeToken = null;
        long lastSeq = 0L;

        try (ArcpClient client1 = ArcpClient.builder(pair1.client()).build()) {
            client1.connect(Duration.ofSeconds(5));
            JobHandle handle =
                    client1.submit(
                            ArcpClient.jobSubmit(
                                    "echo@1.0.0",
                                    JsonNodeFactory.instance.objectNode().put("pass", 1)));
            handle.result().get(5, TimeUnit.SECONDS);

            Session session = client1.session();
            resumeToken = session.resumeToken();
            lastSeq = client1.lastSeenSeq();
        }

        // Second connection: optionally resume from captured token.
        MemoryTransport.Pair pair2 = MemoryTransport.pair();
        runtime.accept(pair2.runtime());

        ArcpClient.Builder builder2 = ArcpClient.builder(pair2.client());
        if (resumeToken != null) {
            builder2 = builder2.resumeToken(resumeToken).lastEventSeq(lastSeq);
        }

        try (ArcpClient client2 = builder2.build()) {
            client2.connect(Duration.ofSeconds(5));
            JobHandle handle =
                    client2.submit(
                            ArcpClient.jobSubmit(
                                    "echo@1.0.0",
                                    JsonNodeFactory.instance.objectNode().put("pass", 2)));
            JobResult result = handle.result().get(5, TimeUnit.SECONDS);
            assert result.result().get("pass").asInt() == 2
                    : "unexpected result: " + result.result();
            System.out.println("OK resume");
        }
        runtime.close();
    }
}
