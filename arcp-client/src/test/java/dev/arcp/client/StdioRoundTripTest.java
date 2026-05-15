package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.transport.StdioTransport;
import dev.arcp.core.transport.Transport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * §4.2 stdio transport round-trip: parent JVM hosts both peers wired through
 * a {@link QueuePipe} that mirrors a child process's stdin/stdout without
 * the thread-affinity pitfalls of {@code java.io.PipedInputStream}.
 */
class StdioRoundTripTest {

    @Test
    void runtimeAndClientExchangeOverPipes() throws Exception {
        QueuePipe.Pair pipe = QueuePipe.pair();
        Transport runtimeTransport =
                new StdioTransport(pipe.aIn, pipe.aOut).start();
        Transport clientTransport =
                new StdioTransport(pipe.bIn, pipe.bOut).start();

        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("stdio-echo", "1.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                .build();
        runtime.accept(runtimeTransport);

        try (ArcpClient client = ArcpClient.builder(clientTransport).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                    "stdio-echo@1.0.0",
                    JsonNodeFactory.instance.objectNode().put("ping", "pong")));
            var result = handle.result().get(5, TimeUnit.SECONDS);
            assertThat(result.result().get("ping").asText()).isEqualTo("pong");
        }
        runtime.close();
    }
}
