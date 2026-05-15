package dev.arcp.examples.submitandstream;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.Session;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Submits a job, streams its events, and prints the assembled final result. */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("emitter", "1.0.0", (input, ctx) -> {
                    ctx.emit(new StatusEvent("starting", null));
                    for (int i = 1; i <= 3; i++) {
                        ctx.emit(new LogEvent("info", "step " + i));
                        Thread.sleep(50);
                    }
                    return JobOutcome.Success.inline(input.payload());
                })
                .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            Session session = client.connect(Duration.ofSeconds(5));
            assert session.sessionId() != null;

            ObjectNode input = JsonNodeFactory.instance.objectNode();
            input.put("greeting", "hello");
            JobHandle handle = client.submit(ArcpClient.jobSubmit("emitter@1.0.0", input));
            handle.events().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(EventBody body) {
                    System.out.println("event: " + body);
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
            });
            JobResult result = handle.result().get(5, TimeUnit.SECONDS);
            assert "success".equals(result.finalStatus());
            assert "hello".equals(result.result().get("greeting").asText());
            System.out.println("OK submit-and-stream");
        }
        runtime.close();
    }
}
