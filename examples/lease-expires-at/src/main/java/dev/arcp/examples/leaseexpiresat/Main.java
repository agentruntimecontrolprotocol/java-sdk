package dev.arcp.examples.leaseexpiresat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.error.LeaseExpiredException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Lease expires while the agent is still running; runtime emits LEASE_EXPIRED. */
public final class Main {
    public static void main(String[] args) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("idle", "1.0.0", (input, ctx) -> {
                    release.await(5, TimeUnit.SECONDS);
                    return JobOutcome.Success.inline(input.payload());
                })
                .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            Lease lease = Lease.builder().allow("tool.call", "*").build();
            LeaseConstraints constraints = LeaseConstraints.of(
                    Instant.now().plusSeconds(1));
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                    "idle@1.0.0",
                    JsonNodeFactory.instance.objectNode(),
                    lease,
                    constraints,
                    null,
                    null));
            try {
                handle.result().get(5, TimeUnit.SECONDS);
                throw new AssertionError("expected LeaseExpiredException");
            } catch (ExecutionException e) {
                assert e.getCause() instanceof LeaseExpiredException
                        : "got " + e.getCause();
            }
            release.countDown();
            System.out.println("OK lease-expires-at");
        }
        runtime.close();
    }
}
