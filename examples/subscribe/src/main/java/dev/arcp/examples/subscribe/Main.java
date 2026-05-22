package dev.arcp.examples.subscribe;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.SubscribeOptions;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates event subscription with history replay: client A submits a job that emits
 * StatusEvents; client B subscribes to the completed job's event stream and replays all events.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair1 = MemoryTransport.pair();
        MemoryTransport[] pair2 = MemoryTransport.pair();

        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "ticker",
                                "1.0.0",
                                (input, ctx) -> {
                                    for (int i = 1; i <= 5; i++) {
                                        ctx.emit(new StatusEvent("tick-" + i, null));
                                    }
                                    return JobOutcome.Success.inline(input.payload());
                                })
                        .build();
        runtime.accept(pair1[0]);
        runtime.accept(pair2[0]);

        JobId jobId;

        // Client A: submit the job and wait for it to complete.
        try (ArcpClient clientA = ArcpClient.builder(pair1[1]).bearer("demo").build()) {
            clientA.connect(Duration.ofSeconds(5));
            JobHandle handle =
                    clientA.submit(
                            ArcpClient.jobSubmit(
                                    "ticker@1.0.0", JsonNodeFactory.instance.objectNode()));
            handle.result().get(5, TimeUnit.SECONDS);
            jobId = handle.jobId();
        }

        // Client B: subscribe to the completed job with full history replay.
        AtomicInteger replayCount = new AtomicInteger();
        CompletableFuture<Void> allReplayed = new CompletableFuture<>();

        try (ArcpClient clientB = ArcpClient.builder(pair2[1]).bearer("demo").build()) {
            clientB.connect(Duration.ofSeconds(5));

            Flow.Publisher<EventBody> events =
                    clientB.subscribe(jobId, SubscribeOptions.withHistory(0L));
            events.subscribe(
                    new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription s) {
                            s.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(EventBody body) {
                            if (body instanceof StatusEvent) {
                                if (replayCount.incrementAndGet() == 5) {
                                    allReplayed.complete(null);
                                }
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            allReplayed.completeExceptionally(t);
                        }

                        @Override
                        public void onComplete() {}
                    });

            allReplayed.get(5, TimeUnit.SECONDS);
            assert replayCount.get() == 5 : "expected 5 replayed events, got " + replayCount.get();
            System.out.println("OK subscribe");
        }
        runtime.close();
    }
}
