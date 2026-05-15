package dev.arcp.examples.cancel;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.error.ArcpException;
import dev.arcp.core.error.CancelledException;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Submits a long-running job, then cancels it; the resulting future fails with CancelledException. */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("sleeper", "1.0.0", (input, ctx) -> {
                    ctx.emit(new LogEvent("info", "sleeping"));
                    while (!ctx.cancelled()) {
                        Thread.sleep(50);
                    }
                    return new JobOutcome.Failure(
                            dev.arcp.core.error.ErrorCode.CANCELLED, "cooperative");
                })
                .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle = client.submit(
                    ArcpClient.jobSubmit("sleeper@1.0.0", JsonNodeFactory.instance.objectNode()));
            Thread.sleep(100);
            handle.cancel();

            try {
                handle.result().get(5, TimeUnit.SECONDS);
                throw new AssertionError("expected cancellation");
            } catch (ExecutionException e) {
                assert e.getCause() instanceof CancelledException
                        : "expected CancelledException, got " + e.getCause();
                System.out.println("OK cancel");
            }
        }
        runtime.close();
    }
}
