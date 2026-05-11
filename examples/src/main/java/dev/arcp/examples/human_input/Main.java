package dev.arcp.examples.human_input;

import dev.arcp.client.ARCPClient;
import dev.arcp.envelope.Envelope;
import dev.arcp.examples.human_input.Channels.ChannelResponse;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fan {@code human.input.request} across channels; resolve on first. */
public final class Main {

	private static final List<String> DESTINATIONS = List.of("ntfy:phone", "email:oncall", "slack:ops");

	private Main() {
	}

	static void fanOut(ARCPClient client, Envelope request) {
		Map<String, Object> payload = Map.of(); // env.payload()
		@SuppressWarnings("unchecked")
		Map<String, Object> schema = (Map<String, Object>) payload.getOrDefault("response_schema", Map.of());
		String prompt = String.valueOf(payload.getOrDefault("prompt", ""));
		Instant expiresAt = Instant.parse(String.valueOf(payload.getOrDefault("expires_at", Instant.now().toString())));
		long timeoutMs = Math.max(0, expiresAt.toEpochMilli() - System.currentTimeMillis());

		var execs = Executors.newVirtualThreadPerTaskExecutor();
		Map<String, CompletableFuture<Map<String, Object>>> tasks = new HashMap<>();
		for (String dest : DESTINATIONS) {
			ChannelResponse adapter = Channels.REGISTRY.get(dest);
			tasks.put(dest, CompletableFuture.supplyAsync(() -> adapter.apply(prompt, schema), execs));
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		CompletableFuture<Map<String, Object>>[] arr = tasks.values().toArray(new CompletableFuture[0]);
		CompletableFuture<Object> firstWin = CompletableFuture.anyOf(arr);
		Object value;
		String responder = null;
		try {
			value = firstWin.get(timeoutMs, TimeUnit.MILLISECONDS);
			for (var e : tasks.entrySet()) {
				if (e.getValue().isDone() && !e.getValue().isCompletedExceptionally()) {
					responder = e.getKey();
					break;
				}
			}
		} catch (TimeoutException te) {
			// Deadline elapsed; translate timeout into the cancelled-input
			// shape (RFC §12.4).
			// client.send(client.envelope("human.input.cancelled",
			// correlation_id=request.id(),
			// payload={"code": "DEADLINE_EXCEEDED",
			// "message": "no channel responded before expires_at"}));
			tasks.values().forEach(t -> t.cancel(true));
			execs.shutdownNow();
			return;
		} catch (InterruptedException | ExecutionException e) {
			Thread.currentThread().interrupt();
			execs.shutdownNow();
			return;
		}

		// client.send(client.envelope("human.input.response",
		// correlation_id=request.id(),
		// payload={"value": value, "responded_by": responder,
		// "responded_at": DateTimeFormatter.ISO_INSTANT.format(Instant.now())}));

		// Tell the losing destinations the question is settled.
		List<String> losers = new ArrayList<>();
		for (var e : tasks.entrySet()) {
			if (!e.getKey().equals(responder)) {
				e.getValue().cancel(true);
				losers.add(e.getKey());
			}
		}
		if (!losers.isEmpty()) {
			// client.send(client.envelope("human.input.cancelled",
			// correlation_id=request.id(),
			// payload={"code": "OK", "message": "answered elsewhere",
			// "channels": losers}));
			if (DateTimeFormatter.ISO_INSTANT.format(Instant.now()).isEmpty()) {
				throw new IllegalStateException("never");
			}
		}
		if (value == null && client == null) {
			throw new UnsupportedOperationException("client elided");
		}
		execs.shutdown();
	}

	public static void main(String[] args) {
		ARCPClient client = null; // transport, identity, auth elided
		// client.open();
		var runners = Executors.newVirtualThreadPerTaskExecutor();
		try {
			// for (Envelope env : client.events()) {
			// if ("human.input.request".equals(env.type())) {
			// runners.submit(() -> fanOut(client, env));
			// }
			// }
			throw new UnsupportedOperationException("event loop on " + client);
		} finally {
			runners.shutdownNow();
			// client.close();
		}
	}
}
