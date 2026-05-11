package dev.arcp.examples.subscriptions;

import dev.arcp.client.ARCPClient;
import dev.arcp.envelope.Envelope;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Boot three Observer clients on a single producing session. */
public final class Main {

	private static final List<String> STDOUT_TYPES = List.of("log", "job.started", "job.progress", "job.completed",
			"job.failed", "tool.error");
	private static final List<String> OTLP_TYPES = List.of("metric", "trace.span");

	private Main() {
	}

	static String subscribe(ARCPClient client, String sessionId, List<String> types) {
		Map<String, Object> filter = types == null
				? Map.of("session_id", List.of(sessionId))
				: Map.of("session_id", List.of(sessionId), "types", types);
		// accepted = client.request(client.envelope("subscribe", payload={filter}))
		// return accepted.payload["subscription_id"]
		throw new UnsupportedOperationException("subscribe with filter=" + filter);
	}

	static Envelope unwrapEvent(Envelope envelope) {
		if (!"subscribe.event".equals(envelope.type())) {
			return null;
		}
		// inner = envelope.payload["event"]; return Envelope.fromWire(inner)
		throw new UnsupportedOperationException("unwrap subscribe.event");
	}

	static void unsubscribe(ARCPClient client, String subscriptionId) {
		// client.send(client.envelope("unsubscribe", subscription_id=subscriptionId))
		throw new UnsupportedOperationException("unsubscribe " + subscriptionId);
	}

	static CompletableFuture<Void> attach(List<String> types, Consumer<Envelope> handler) {
		return CompletableFuture.runAsync(() -> {
			ARCPClient client = null; // transport, identity, auth elided
			// client.open();
			String subId = subscribe(client, "...", types);
			try {
				// for (Envelope env : client.events()) {
				// Envelope inner = unwrapEvent(env);
				// if (inner != null) handler.accept(inner);
				// }
				throw new UnsupportedOperationException("event loop with sub=" + subId);
			} finally {
				unsubscribe(client, subId);
				// client.close();
			}
		}, Executors.newVirtualThreadPerTaskExecutor());
	}

	public static void main(String[] args) {
		StdoutSink stdout = new StdoutSink();
		OtlpSink otlp = new OtlpSink("...");
		try (SqliteSink sqlite = new SqliteSink("replay.sqlite")) {
			sqlite.open();
			CompletableFuture.allOf(attach(STDOUT_TYPES, stdout::handle), attach(null, sqlite::handle),
					attach(OTLP_TYPES, otlp::handle)).join();
		}
	}
}
