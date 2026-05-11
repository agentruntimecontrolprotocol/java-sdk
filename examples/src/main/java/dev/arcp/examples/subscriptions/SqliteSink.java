package dev.arcp.examples.subscriptions;

import dev.arcp.envelope.Envelope;

/** SQLite replay sink. Reuses the SDK's {@code dev.arcp.store} schema. */
public final class SqliteSink implements AutoCloseable {

	private final String path;

	public SqliteSink(String path) {
		this.path = path;
	}

	public void open() {
		// Real version: JDBC connect + executescript(schema.sql).
		throw new UnsupportedOperationException("stub");
	}

	public void handle(Envelope env) {
		// Drops kind: thought to keep the replay store small.
		throw new UnsupportedOperationException("stub: write to " + path);
	}

	@Override
	public void close() {
		// Real version: connection.close()
	}
}
