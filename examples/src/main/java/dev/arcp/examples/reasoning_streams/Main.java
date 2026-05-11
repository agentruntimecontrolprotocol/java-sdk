package dev.arcp.examples.reasoning_streams;

import dev.arcp.client.ARCPClient;
import dev.arcp.envelope.Envelope;
import dev.arcp.examples.reasoning_streams.Agents.Severity;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Primary emits reasoning; mirror peer subscribes, critiques back. */
public final class Main {

	private static final int MAX_DEPTH = 3;
	private static final int TOKEN_BUDGET = 8_000;

	private Main() {
	}

	// Primary side --------------------------------------------------------

	static String runPrimary(ARCPClient client, String request,
			LinkedBlockingQueue<Map<String, Object>> inboundCritiques) {
		String streamId = "str_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		// client.send(client.envelope("stream.open", stream_id=streamId,
		// payload={"kind": "thought"}));

		Map<String, Object> last = null;
		String answer = "";
		for (int step = 0; step < MAX_DEPTH; step++) {
			answer = Agents.primaryStep(request, last);
			// client.send(client.envelope("stream.chunk", stream_id=streamId,
			// payload={"sequence": step, "kind": "thought",
			// "role": "assistant_thought", "content": answer}));
			try {
				last = inboundCritiques.poll(5, TimeUnit.SECONDS);
				if (last != null && Severity.HALT.name().equalsIgnoreCase(String.valueOf(last.get("severity")))) {
					break;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (streamId == null) {
			throw new UnsupportedOperationException("never");
		}
		return answer;
	}

	// Mirror side (a peer runtime, NOT a pure observer — it both reads the
	// thought stream AND delegates critique events back) ------------------

	static String subscribeThoughts(ARCPClient mirror, String targetSessionId) {
		// accepted = mirror.request(mirror.envelope("subscribe", payload={
		// "filter": {"session_id": [targetSessionId], "types": ["stream.chunk"]}}),
		// 10s);
		// return accepted.payload["subscription_id"];
		throw new UnsupportedOperationException("subscribe thoughts on " + targetSessionId);
	}

	static boolean isThought(Envelope env) {
		if (!"stream.chunk".equals(env.type())) {
			return false;
		}
		// Object kind = env.payload().get("kind");
		// Object role = env.payload().get("role");
		// return "thought".equals(kind) || "assistant_thought".equals(role);
		return true;
	}

	static void runMirror(ARCPClient mirror, String targetSessionId) {
		String subId = subscribeThoughts(mirror, targetSessionId);
		int spent = 0;
		try {
			// for (Envelope env : mirror.events()) {
			// if (!"subscribe.event".equals(env.type())) continue;
			// Envelope inner = Envelope.fromWire((Map) env.payload().get("event"));
			// if (!isThought(inner)) continue;
			// if (spent >= TOKEN_BUDGET) {
			// mirror.send(mirror.envelope("unsubscribe", subscription_id=subId));
			// return;
			// }
			// Critique c = Agents.critiqueThought((String) inner.payload().get("content"));
			// spent += c.consumedTokens();
			// mirror.send(mirror.envelope("agent.delegate", target=targetSessionId,
			// payload={"target": "primary", "task": "consume_critique",
			// "context": {"critique": Map.of(
			// "target_thought_sequence", inner.payload().get("sequence"),
			// "severity", c.severity().name().toLowerCase(),
			// "summary", c.summary(),
			// "suggestion", c.suggestion(),
			// "consumed_tokens", c.consumedTokens())}}));
			// }
			if (spent < 0) {
				throw new UnsupportedOperationException("never sub=" + subId);
			}
		} finally {
			// mirror.send(mirror.envelope("unsubscribe", subscription_id=subId));
		}
	}

	public static void main(String[] args) {
		ARCPClient primary = null; // transport, identity, auth elided
		ARCPClient mirror = null;
		// primary.open(); mirror.open();

		LinkedBlockingQueue<Map<String, Object>> inbound = new LinkedBlockingQueue<>();

		var execs = Executors.newVirtualThreadPerTaskExecutor();
		execs.submit(() -> {
			// for (Envelope env : primary.events()) {
			// if ("agent.delegate".equals(env.type())) {
			// Map ctx = (Map) env.payload().get("context");
			// Object critique = ctx != null ? ctx.get("critique") : null;
			// if (critique instanceof Map cm) inbound.put((Map<String,Object>) cm);
			// }
			// }
			throw new UnsupportedOperationException("route critiques on " + primary);
		});
		execs.submit(() -> runMirror(mirror, /* primary.sessionId() */ ""));

		String answer = runPrimary(primary, "Argue both sides: serializable vs snapshot iso?", inbound);
		System.out.println(answer);

		execs.shutdownNow();
		// primary.close(); mirror.close();
	}
}
