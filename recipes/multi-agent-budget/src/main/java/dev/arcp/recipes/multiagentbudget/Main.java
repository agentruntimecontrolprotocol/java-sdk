package dev.arcp.recipes.multiagentbudget;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.error.BudgetExhaustedException;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Recipe: Multi-agent cost budgeting.
 *
 * <p>Two agents share a USD 5.00 budget. The cheap agent (USD 1.00) completes successfully; the
 * expensive agent (USD 8.00) triggers {@link BudgetExhaustedException}. This demonstrates how
 * leases enforce spending limits across different agent calls.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "cheap-agent",
                                "1.0.0",
                                (input, ctx) -> {
                                    ctx.emit(
                                            new MetricEvent(
                                                    "cost.inference",
                                                    new BigDecimal("1.00"),
                                                    "USD",
                                                    null));
                                    ctx.authorize("tool.call", "*");
                                    return JobOutcome.Success.inline(
                                            JsonNodeFactory.instance
                                                    .objectNode()
                                                    .put("agent", "cheap"));
                                })
                        .agent(
                                "expensive-agent",
                                "1.0.0",
                                (input, ctx) -> {
                                    ctx.emit(
                                            new MetricEvent(
                                                    "cost.inference",
                                                    new BigDecimal("8.00"),
                                                    "USD",
                                                    null));
                                    ctx.authorize("tool.call", "*");
                                    return JobOutcome.Success.inline(
                                            JsonNodeFactory.instance
                                                    .objectNode()
                                                    .put("agent", "expensive"));
                                })
                        .build();
        runtime.accept(pair[0]);

        // Lease: allow all tool calls but cap total spend at USD 5.00.
        Lease lease =
                Lease.builder()
                        .allow("tool.call", "*")
                        .allow("cost.budget", "USD:5.00")
                        .build();

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));

            // Cheap agent (USD 1.00) should succeed within budget.
            JobHandle cheapHandle =
                    client.submit(
                            ArcpClient.jobSubmit(
                                    "cheap-agent@1.0.0",
                                    JsonNodeFactory.instance.objectNode(),
                                    lease,
                                    null,
                                    null,
                                    null));
            JobResult cheapResult = cheapHandle.result().get(5, TimeUnit.SECONDS);
            assert "cheap".equals(cheapResult.result().get("agent").asText())
                    : "unexpected result: " + cheapResult.result();

            // Expensive agent (USD 8.00) should fail with BudgetExhaustedException.
            JobHandle expensiveHandle =
                    client.submit(
                            ArcpClient.jobSubmit(
                                    "expensive-agent@1.0.0",
                                    JsonNodeFactory.instance.objectNode(),
                                    lease,
                                    null,
                                    null,
                                    null));
            try {
                expensiveHandle.result().get(5, TimeUnit.SECONDS);
                throw new AssertionError("expected BudgetExhaustedException");
            } catch (ExecutionException e) {
                assert e.getCause() instanceof BudgetExhaustedException
                        : "expected BudgetExhaustedException, got " + e.getCause();
            }

            System.out.println("OK multi-agent-budget");
        }
        runtime.close();
    }
}
