package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.error.DuplicateKeyException;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IdempotencyTest {

    @Test
    void identicalKeyAndPayloadReusesJobId() throws Exception {
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("noop", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                .build();

        try (var client = paired(runtime)) {
            client.connect(Duration.ofSeconds(5));
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("x", 1);
            JobHandle first = client.submit(dev.arcp.client.ArcpClient.jobSubmit(
                    "noop@1.0.0", payload, null, null, "weekly-2026-W19", null));
            JobHandle second = client.submit(dev.arcp.client.ArcpClient.jobSubmit(
                    "noop@1.0.0", payload, null, null, "weekly-2026-W19", null));
            assertThat(second.jobId()).isEqualTo(first.jobId());
        }
        runtime.close();
    }

    @Test
    void conflictingPayloadYieldsDuplicateKey() throws Exception {
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("noop", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                .build();

        try (var client = paired(runtime)) {
            client.connect(Duration.ofSeconds(5));
            ObjectNode first = JsonNodeFactory.instance.objectNode();
            first.put("x", 1);
            client.submit(dev.arcp.client.ArcpClient.jobSubmit(
                    "noop@1.0.0", first, null, null, "key-collision", null));

            ObjectNode second = JsonNodeFactory.instance.objectNode();
            second.put("x", 2);
            assertThatThrownBy(() -> client.submit(dev.arcp.client.ArcpClient.jobSubmit(
                            "noop@1.0.0", second, null, null, "key-collision", null)))
                    .hasCauseInstanceOf(DuplicateKeyException.class);
        }
        runtime.close();
    }

    private static ArcpClient paired(ArcpRuntime runtime) {
        MemoryTransport[] pair = MemoryTransport.pair();
        runtime.accept(pair[0]);
        return ArcpClient.builder(pair[1]).build();
    }
}
