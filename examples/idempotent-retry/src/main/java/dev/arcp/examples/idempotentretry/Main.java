package dev.arcp.examples.idempotentretry;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.error.DuplicateKeyException;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;

/** Submits the same job twice with the same idempotency_key; second returns same job_id. */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("report", "1.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("week", 19);

            JobHandle first = client.submit(ArcpClient.jobSubmit(
                    "report@1.0.0", payload, null, null, "weekly-2026-W19", null));
            JobHandle second = client.submit(ArcpClient.jobSubmit(
                    "report@1.0.0", payload, null, null, "weekly-2026-W19", null));
            assert second.jobId().equals(first.jobId())
                    : "ids differ: " + first.jobId() + " vs " + second.jobId();

            ObjectNode conflicting = JsonNodeFactory.instance.objectNode();
            conflicting.put("week", 20);
            try {
                client.submit(ArcpClient.jobSubmit(
                        "report@1.0.0", conflicting, null, null,
                        "weekly-2026-W19", null));
                throw new AssertionError("expected DuplicateKeyException");
            } catch (RuntimeException e) {
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assert root instanceof DuplicateKeyException
                        : "expected DuplicateKeyException, got " + root;
            }

            System.out.println("OK idempotent-retry");
        }
        runtime.close();
    }
}
