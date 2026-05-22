package dev.arcp.examples.stdio;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.StdioTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the StdioTransport: runtime and client communicate over cross-wired
 * PipedInputStream/PipedOutputStream pairs, mimicking a subprocess stdio channel.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        // Cross-wire: serverOut → clientIn, clientOut → serverIn.
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedOutputStream clientOut = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientOut);
        PipedInputStream clientIn = new PipedInputStream(serverOut);

        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "stdio-echo",
                                "1.0.0",
                                (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                        .build();
        runtime.accept(new StdioTransport(serverIn, serverOut).start());

        try (ArcpClient client =
                ArcpClient.builder(new StdioTransport(clientIn, clientOut).start()).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle =
                    client.submit(
                            ArcpClient.jobSubmit(
                                    "stdio-echo@1.0.0",
                                    JsonNodeFactory.instance.objectNode().put("via", "stdio")));
            JobResult result = handle.result().get(5, TimeUnit.SECONDS);
            assert "stdio".equals(result.result().get("via").asText())
                    : "unexpected result: " + result.result();
            System.out.println("OK stdio");
        }
        runtime.close();
    }
}
