package dev.arcp.examples.costbudget;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.error.BudgetExhaustedException;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Tracks a USD budget; agent over-spends and surfaces BUDGET_EXHAUSTED. */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("spender", "1.0.0", (input, ctx) -> {
                    // Spend over budget by accumulating cost.inference metrics.
                    for (int i = 0; i < 10; i++) {
                        ctx.emit(new MetricEvent(
                                "cost.inference", new BigDecimal("0.75"),
                                "USD", null));
                        ctx.authorize("tool.call", "*");
                    }
                    return JobOutcome.Success.inline(input.payload());
                })
                .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            Lease lease = Lease.builder()
                    .allow("tool.call", "*")
                    .allow("cost.budget", "USD:5.00")
                    .build();
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                    "spender@1.0.0",
                    JsonNodeFactory.instance.objectNode(),
                    lease,
                    null,
                    null,
                    null));
            try {
                handle.result().get(5, TimeUnit.SECONDS);
                throw new AssertionError("expected BudgetExhaustedException");
            } catch (ExecutionException e) {
                assert e.getCause() instanceof BudgetExhaustedException
                        : "got " + e.getCause();
                System.out.println("OK cost-budget");
            }
        }
        runtime.close();
    }
}
