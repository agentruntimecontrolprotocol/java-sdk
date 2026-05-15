package dev.arcp.examples.listjobs;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.Page;
import dev.arcp.core.messages.JobFilter;
import dev.arcp.core.messages.JobSummary;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Submits two jobs, lists them, and validates filtering by status. */
public final class Main {
    public static void main(String[] args) throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("blocker", "1.0.0", (input, ctx) -> {
                    hold.await();
                    return JobOutcome.Success.inline(input.payload());
                })
                .agent("fast", "1.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            var fast = client.submit(ArcpClient.jobSubmit(
                    "fast@1.0.0", JsonNodeFactory.instance.objectNode()));
            fast.result().get(5, TimeUnit.SECONDS);

            client.submit(ArcpClient.jobSubmit(
                    "blocker@1.0.0", JsonNodeFactory.instance.objectNode()));

            Page<JobSummary> all = client.listJobs(JobFilter.all());
            assert all.items().size() == 2 : "expected 2 jobs, got " + all.items().size();

            Page<JobSummary> running = client.listJobs(new JobFilter(
                    java.util.List.of("running"), null, null));
            assert running.items().size() == 1
                    : "expected 1 running, got " + running.items().size();
            assert running.items().get(0).agent().startsWith("blocker@");

            hold.countDown();
            System.out.println("OK list-jobs");
        }
        runtime.close();
    }
}
