package dev.arcp.examples.subscriptions;

import dev.arcp.envelope.Envelope;

/** Stdout sink — production version uses SLF4J / structured logging. */
public final class StdoutSink {
	public void handle(Envelope env) {
		// Real version: logger.info(env.type(), env.payload());
		throw new UnsupportedOperationException("stub");
	}
}
