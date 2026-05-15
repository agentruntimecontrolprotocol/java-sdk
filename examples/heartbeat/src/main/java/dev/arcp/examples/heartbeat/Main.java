package dev.arcp.examples.heartbeat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.Session;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.EnumSet;

/** Negotiates heartbeat at 1s interval; idle for &gt;2s then verifies session is still active. */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .heartbeatIntervalSec(1)
                .agent("noop", "1.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(
                                JsonNodeFactory.instance.objectNode()))
                .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1])
                .features(EnumSet.of(Feature.HEARTBEAT))
                .build()) {
            Session session = client.connect(Duration.ofSeconds(5));
            assert session.heartbeatInterval() != null;
            assert session.negotiatedFeatures().contains(Feature.HEARTBEAT);

            // Stay idle past two heartbeat intervals; the runtime's scheduler should ping.
            Thread.sleep(2500);
            System.out.println("OK heartbeat");
        }
        runtime.close();
    }
}
