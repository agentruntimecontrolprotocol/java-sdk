package dev.arcp.examples.leaseviolation;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates lease enforcement: the agent requests a capability not covered by the client's
 * lease; the runtime surfaces PERMISSION_DENIED and the client sees PermissionDeniedException.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "writer",
                                "1.0.0",
                                (input, ctx) -> {
                                    // Requests fs.write but the lease only grants fs.read.
                                    ctx.authorize("fs.write", "/tmp/data");
                                    return JobOutcome.Success.inline(input.payload());
                                })
                        .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));

            Lease lease = Lease.builder().allow("fs.read", "*").build();
            JobHandle handle =
                    client.submit(
                            ArcpClient.jobSubmit(
                                    "writer@1.0.0",
                                    JsonNodeFactory.instance.objectNode(),
                                    lease,
                                    null,
                                    null,
                                    null));

            try {
                handle.result().get(5, TimeUnit.SECONDS);
                throw new AssertionError("expected PermissionDeniedException");
            } catch (ExecutionException e) {
                assert e.getCause() instanceof PermissionDeniedException
                        : "expected PermissionDeniedException, got " + e.getCause();
                System.out.println("OK lease-violation");
            }
        }
        runtime.close();
    }
}
