package dev.arcp.examples.resultchunk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.ResultStream;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.ResultChunkEvent;
import dev.arcp.core.ids.ResultId;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Agent streams a multi-chunk result; client reassembles via ResultStream. */
public final class Main {
    public static void main(String[] args) throws Exception {
        ResultId resultId = ResultId.of("res_chunked");
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("chunked", "1.0.0", (input, ctx) -> {
                    ctx.emit(new ResultChunkEvent(resultId, 0, "hello ", "utf8", true));
                    ctx.emit(new ResultChunkEvent(resultId, 1, "result-chunk ", "utf8", true));
                    ctx.emit(new ResultChunkEvent(resultId, 2, "world", "utf8", false));
                    return JobOutcome.Success.streamed(resultId, 18, "3 chunks");
                })
                .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                    "chunked@1.0.0", JsonNodeFactory.instance.objectNode()));

            ResultStream stream = ResultStream.toMemory(resultId);
            CountDownLatch done = new CountDownLatch(1);
            handle.events().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(EventBody body) {
                    if (body instanceof ResultChunkEvent chunk) {
                        try {
                            stream.accept(chunk);
                            if (stream.isComplete()) {
                                done.countDown();
                            }
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    }
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
            });

            handle.result().get(5, TimeUnit.SECONDS);
            assert done.await(2, TimeUnit.SECONDS);
            String reassembled = new String(stream.bytes(), StandardCharsets.UTF_8);
            assert "hello result-chunk world".equals(reassembled) : "got: " + reassembled;
            System.out.println("OK result-chunk");
        }
        runtime.close();
    }
}
