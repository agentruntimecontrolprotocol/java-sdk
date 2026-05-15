package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.auth.BearerVerifier;
import dev.arcp.core.auth.Principal;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class SmokeRoundTripTest {

    @Test
    void inProcessJobRoundTrip() throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        MemoryTransport runtimeSide = pair[0];
        MemoryTransport clientSide = pair[1];

        ArcpRuntime runtime = ArcpRuntime.builder()
                .verifier(BearerVerifier.staticToken("hunter2", new Principal("alice")))
                .agent("echo", "1.0.0", (input, ctx) -> {
                    ctx.emit(new LogEvent("info", "echoing"));
                    return JobOutcome.Success.inline(input.payload());
                })
                .build();
        runtime.accept(runtimeSide);

        try (ArcpClient client = ArcpClient.builder(clientSide).bearer("hunter2").build()) {
            Session session = client.connect(Duration.ofSeconds(5));
            assertThat(session.sessionId()).isNotNull();
            assertThat(session.availableAgents()).extracting("name").contains("echo");

            ObjectNode input = JsonNodeFactory.instance.objectNode();
            input.put("greeting", "hello world");
            JobHandle handle = client.submit(ArcpClient.jobSubmit("echo@1.0.0", input));

            CopyOnWriteArrayList<String> logs = new CopyOnWriteArrayList<>();
            handle.events().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(dev.arcp.core.events.EventBody body) {
                    if (body instanceof LogEvent log) {
                        logs.add(log.message());
                    }
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
            });

            JobResult result = handle.result().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.finalStatus()).isEqualTo(JobResult.SUCCESS);
            JsonNode payload = result.result();
            assertThat(payload).isNotNull();
            assertThat(payload.get("greeting").asText()).isEqualTo("hello world");

            await().atMost(Duration.ofSeconds(2)).until(() -> logs.contains("echoing"));
        }

        runtime.close();
    }
}
