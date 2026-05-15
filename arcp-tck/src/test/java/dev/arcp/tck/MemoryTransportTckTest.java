package dev.arcp.tck;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs {@link ConformanceSuite} against a vanilla in-process runtime + paired
 * {@link MemoryTransport}. Self-validation that the harness terminates and
 * that the reference runtime satisfies it.
 */
class MemoryTransportTckTest {

    @TestFactory
    Stream<DynamicTest> conformance() {
        List<DynamicTest> tests = ConformanceSuite.dynamicTests(InProcessProvider::new);
        return tests.stream();
    }

    private static final class InProcessProvider implements TckProvider {
        private final ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("tck-echo", "1.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                .agent("tck-log-emitter", "1.0.0",
                        (input, ctx) -> {
                            ctx.emit(new LogEvent("info", "tck-emit"));
                            return JobOutcome.Success.inline(
                                    JsonNodeFactory.instance.objectNode());
                        })
                .build();

        @Override
        public ArcpClient connect() {
            MemoryTransport[] pair = MemoryTransport.pair();
            runtime.accept(pair[0]);
            return ArcpClient.builder(pair[1]).build();
        }

        @Override
        public void close() {
            runtime.close();
        }
    }
}
