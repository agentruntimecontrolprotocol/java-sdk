package dev.arcp.examples.subscriptions;

import dev.arcp.envelope.Envelope;

/**
 * OTLP exporter for {@code metric} and {@code trace.span} envelopes (RFC §17).
 */
public final class OtlpSink {

	private final String endpoint;

	public OtlpSink(String endpoint) {
		this.endpoint = endpoint;
		// Real version: opentelemetry-exporter-otlp + meter/tracer providers.
	}

	public void handle(Envelope env) {
		switch (env.type()) {
			case "metric" -> {
				// Standard names (§17.3.1) → OTLP counters / histograms.
			}
			case "trace.span" -> {
				// mirrors OpenTelemetry span shape.
			}
			default -> {
				/* ignore */
			}
		}
		throw new UnsupportedOperationException("stub: post to " + endpoint);
	}
}
