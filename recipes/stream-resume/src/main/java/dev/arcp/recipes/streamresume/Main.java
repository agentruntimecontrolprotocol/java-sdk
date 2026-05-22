package dev.arcp.recipes.streamresume;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.Session;
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
 * Recipe: Stream resume across connections.
 *
 * <p>Client 1 submits a streaming job (emits 10 {@link StatusEvent}s) and awaits completion,
 * recording the resume token and last-seen sequence. Client 2 reconnects using those values and
 * re-subscribes to replay the event history, demonstrating fault-tolerant stream consumption.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "streamer",
                                "1.0.0",
                                (input, ctx) -> {
                                    for (int i = 1; i <= 10; i++) {
                                        ctx.emit(new StatusEvent("event-" + i, null));
                                    }
                                    return JobOutcome.Success.inline(input.payload());
                                })
                        .build();

        // --- Client 1: live consumption. ---
        MemoryTransport[] pair1 = MemoryTransport.pair();
        runtime.accept(pair1[0]);

        String resumeToken = null;
        long lastSeq = 0L;
        JobId jobId;
        AtomicInteger firstPassCount = new AtomicInteger();
        CompletableFuture<Void> firstPassDone = new CompletableFuture<>();

        try (ArcpClient client1 = ArcpClient.builder(pair1[1]).build()) {
            client1.connect(Duration.ofSeconds(5));

            JobHandle handle =
                    client1.submit(
                            ArcpClient.jobSubmit(
                                    "streamer@1.0.0", JsonNodeFactory.instance.objectNode()));

            handle.events()
                    .subscribe(
                            new Flow.Subscriber<>() {
                                @Override
                                public void onSubscribe(Flow.Subscription s) {
                                    s.request(Long.MAX_VALUE);
                                }

                                @Override
                                public void onNext(EventBody body) {
                                    if (body instanceof StatusEvent) {
                                        if (firstPassCount.incrementAndGet() == 10) {
                                            firstPassDone.complete(null);
                                        }
                                    }
                                }

                                @Override
                                public void onError(Throwable t) {
                                    firstPassDone.completeExceptionally(t);
                                }

                                @Override
                                public void onComplete() {}
                            });

            handle.result().get(5, TimeUnit.SECONDS);
            firstPassDone.get(5, TimeUnit.SECONDS);
            jobId = handle.jobId();

            Session session = client1.session();
            resumeToken = session.resumeToken();
            lastSeq = client1.lastSeenSeq();
        }

        int firstCount = firstPassCount.get();

        // --- Client 2: replay via history subscription. ---
        MemoryTransport[] pair2 = MemoryTransport.pair();
        runtime.accept(pair2[0]);

        AtomicInteger replayCount = new AtomicInteger();
        CompletableFuture<Void> replayDone = new CompletableFuture<>();

        ArcpClient.Builder builder2 = ArcpClient.builder(pair2[1]);
        if (resumeToken != null) {
            builder2 = builder2.resumeToken(resumeToken).lastEventSeq(lastSeq);
        }

        try (ArcpClient client2 = builder2.build()) {
            client2.connect(Duration.ofSeconds(5));

            Flow.Publisher<EventBody> replayEvents =
                    client2.subscribe(jobId, SubscribeOptions.withHistory(0L));
            replayEvents.subscribe(
                    new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription s) {
                            s.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(EventBody body) {
                            if (body instanceof StatusEvent) {
                                if (replayCount.incrementAndGet() == 10) {
                                    replayDone.complete(null);
                                }
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            replayDone.completeExceptionally(t);
                        }

                        @Override
                        public void onComplete() {}
                    });

            replayDone.get(5, TimeUnit.SECONDS);
            int replayTotal = replayCount.get();
            assert replayTotal == 10 : "expected 10 replayed events, got " + replayTotal;
            System.out.println(
                    "OK stream-resume (first-pass=" + firstCount + ", replay=" + replayTotal + ")");
        }
        runtime.close();
    }
}
