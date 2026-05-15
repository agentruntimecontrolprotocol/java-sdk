package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SubscribeReplayTest {

    /**
     * Submits a job whose events have already been buffered before the client
     * subscribes; {@code history: true} must replay them through {@link
     * ArcpClient#subscribe}.
     */
    @Test
    void historyReplaysBufferedEvents() throws Exception {
        CountDownLatch emitted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("slow", "1.0.0", (input, ctx) -> {
                    ctx.emit(new LogEvent("info", "step-1"));
                    emitted.countDown();
                    ctx.emit(new LogEvent("info", "step-2"));
                    emitted.countDown();
                    release.await();
                    return JobOutcome.Success.inline(input.payload());
                })
                .build();

        MemoryTransport[] pair = MemoryTransport.pair();
        runtime.accept(pair[0]);
        try (ArcpClient submitter = ArcpClient.builder(pair[1]).build()) {
            submitter.connect(Duration.ofSeconds(5));
            JobHandle handle = submitter.submit(ArcpClient.jobSubmit(
                    "slow@1.0.0", JsonNodeFactory.instance.objectNode()));

            // Wait for the runtime-side buffer to hold both events before we subscribe.
            assertThat(emitted.await(3, TimeUnit.SECONDS)).isTrue();

            CopyOnWriteArrayList<String> replayed = new CopyOnWriteArrayList<>();
            submitter.subscribe(handle.jobId(), SubscribeOptions.withHistory(0L))
                    .subscribe(new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription s) {
                            s.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(EventBody body) {
                            if (body instanceof LogEvent log) {
                                replayed.add(log.message());
                            }
                        }

                        @Override
                        public void onError(Throwable throwable) {}

                        @Override
                        public void onComplete() {}
                    });

            await().atMost(Duration.ofSeconds(3))
                    .until(() -> replayed.contains("step-1") && replayed.contains("step-2"));
            release.countDown();
            handle.result().get(5, TimeUnit.SECONDS);
        }
        runtime.close();
    }
}
