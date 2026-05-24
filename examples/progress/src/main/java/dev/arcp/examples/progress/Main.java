package dev.arcp.examples.progress;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.ProgressEvent;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates progress reporting: the agent emits ProgressEvent messages as it works through
 * items; the client counts them and asserts all 10 arrive.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport.Pair pair = MemoryTransport.pair();
        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "loader",
                                "1.0.0",
                                (input, ctx) -> {
                                    for (int i = 1; i <= 10; i++) {
                                        ctx.emit(
                                                new ProgressEvent(
                                                        i, 10L, "items", "Processing " + i));
                                    }
                                    return JobOutcome.Success.inline(input.payload());
                                })
                        .build();
        runtime.accept(pair.runtime());

        try (ArcpClient client = ArcpClient.builder(pair.client()).build()) {
            client.connect(Duration.ofSeconds(5));

            AtomicInteger count = new AtomicInteger();
            CompletableFuture<Void> allReceived = new CompletableFuture<>();

            JobHandle handle =
                    client.submit(
                            ArcpClient.jobSubmit(
                                    "loader@1.0.0", JsonNodeFactory.instance.objectNode()));

            handle.events()
                    .subscribe(
                            new Flow.Subscriber<>() {
                                @Override
                                public void onSubscribe(Flow.Subscription s) {
                                    s.request(Long.MAX_VALUE);
                                }

                                @Override
                                public void onNext(EventBody body) {
                                    if (body instanceof ProgressEvent) {
                                        if (count.incrementAndGet() == 10) {
                                            allReceived.complete(null);
                                        }
                                    }
                                }

                                @Override
                                public void onError(Throwable t) {
                                    allReceived.completeExceptionally(t);
                                }

                                @Override
                                public void onComplete() {}
                            });

            handle.result().get(5, TimeUnit.SECONDS);
            allReceived.get(5, TimeUnit.SECONDS);
            assert count.get() == 10 : "expected 10 progress events, got " + count.get();
            System.out.println("OK progress");
        }
        runtime.close();
    }
}
