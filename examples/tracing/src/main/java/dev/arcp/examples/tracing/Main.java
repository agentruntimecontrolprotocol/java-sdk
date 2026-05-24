package dev.arcp.examples.tracing;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.transport.Transport;
import dev.arcp.otel.ArcpOtel;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates OpenTelemetry tracing integration: the client transport is wrapped with
 * {@link ArcpOtel#withTracing}, causing every send/receive to emit spans captured by an
 * {@link InMemorySpanExporter}.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        // Set up an in-memory OTel exporter to capture spans.
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        Tracer tracer =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(provider)
                        .build()
                        .getTracer("arcp-example-tracing");

        MemoryTransport.Pair pair = MemoryTransport.pair();
        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "trace-echo",
                                "1.0.0",
                                (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                        .build();
        runtime.accept(pair.runtime());

        // Wrap the client-side transport with OTel tracing.
        Transport tracingTransport = ArcpOtel.withTracing(pair.client(), tracer);

        try (ArcpClient client = ArcpClient.builder(tracingTransport).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle =
                    client.submit(
                            ArcpClient.jobSubmit(
                                    "trace-echo@1.0.0",
                                    JsonNodeFactory.instance.objectNode().put("msg", "hello")));
            JobResult result = handle.result().get(5, TimeUnit.SECONDS);
            assert "hello".equals(result.result().get("msg").asText())
                    : "unexpected result: " + result.result();
        }

        // Allow spans to flush.
        Thread.sleep(100);

        assert !exporter.getFinishedSpanItems().isEmpty()
                : "expected at least one span to be recorded";
        System.out.println("OK tracing");

        runtime.close();
    }
}
