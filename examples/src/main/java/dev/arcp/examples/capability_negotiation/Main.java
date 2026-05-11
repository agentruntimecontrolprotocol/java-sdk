package dev.arcp.examples.capability_negotiation;

import dev.arcp.client.ARCPClient;
import dev.arcp.envelope.Envelope;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Capability-driven peer routing with ordered fallback + cost rollup. */
public final class Main {

	private static final List<String> PEERS = List.of("anthropic-haiku", "anthropic-sonnet", "openai-4o", "groq-llama");

	private static final Map<String, List<String>> FALLBACK_CHAINS = Map.of("cheap_fast",
			List.of("groq-llama", "anthropic-haiku", "openai-4o"), "balanced",
			List.of("anthropic-sonnet", "openai-4o", "anthropic-haiku"), "deep", List.of("anthropic-sonnet"));

	private static final double COST_CEILING_USD_PER_MTOK = 8.0;
	private static final int LATENCY_CEILING_MS = 800;

	private static final Set<ErrorCode> RETRYABLE = Set.of(ErrorCode.RESOURCE_EXHAUSTED, ErrorCode.UNAVAILABLE,
			ErrorCode.DEADLINE_EXCEEDED, ErrorCode.ABORTED);

	private Main() {
	}

	public record Profile(double costPerMtok, int p50LatencyMs, String modelClass) {
	}

	static Profile profileFrom(Object negotiatedCapabilities) {
		// Capabilities is `extra="allow"` so namespaced fields ride alongside
		// the core booleans. NOTE: §21 covers extension *messages* but not
		// extension *capability values* — load-bearing convention here.
		// var extra = negotiatedCapabilities.extra();
		// return new Profile(
		// ((Number) extra.getOrDefault("arcpx.market.cost_per_mtok.v1",
		// 0)).doubleValue(),
		// ((Number) extra.getOrDefault("arcpx.market.p50_latency_ms.v1",
		// 0)).intValue(),
		// extra.getOrDefault("arcpx.market.model_class.v1", "unknown").toString());
		throw new UnsupportedOperationException("profileFrom " + negotiatedCapabilities);
	}

	static List<String> candidateChain(Map<String, Profile> profiles, String requestClass) {
		List<String> chain = new java.util.ArrayList<>();
		for (String name : FALLBACK_CHAINS.getOrDefault(requestClass, List.of())) {
			Profile p = profiles.get(name);
			if (p != null && p.costPerMtok() <= COST_CEILING_USD_PER_MTOK && p.p50LatencyMs() <= LATENCY_CEILING_MS) {
				chain.add(name);
			}
		}
		return chain;
	}

	/** Walk the chain. Retryable error → next peer; otherwise raise. */
	static Envelope invokeWithFallback(Map<String, ARCPClient> clients, List<String> chain, String tool,
			Map<String, Object> arguments, String traceId) {
		ARCPException last = null;
		for (String name : chain) {
			ARCPClient client = clients.get(name);
			try {
				// Envelope reply = client.request(client.envelope("tool.invoke",
				// trace_id=traceId,
				// extensions=Map.of("arcpx.market.peer.v1", name),
				// payload=Map.of("tool", tool, "arguments", arguments)), 30s);
				// if (!"tool.error".equals(reply.type())) return reply;
				// ErrorCode code = ErrorCode.valueOf((String) reply.payload().get("code"));
				// last = new ARCPException(code, (String)
				// reply.payload().getOrDefault("message", ""));
				// if (RETRYABLE.contains(code)) continue;
				// throw last;
				throw new UnsupportedOperationException("tool.invoke " + tool + " on " + name + " trace=" + traceId
						+ " args=" + arguments + " (last=" + last + ")");
			} catch (ARCPException ex) {
				last = ex;
				if (RETRYABLE.contains(ex.code())) {
					continue;
				}
				throw ex;
			}
		}
		throw last != null ? last : new ARCPException(ErrorCode.UNAVAILABLE, "no peers available");
	}

	public static final class Usage {
		public long tokensIn;
		public long tokensOut;
		public double costUsd;
		public final Map<String, Double> byPeer = new HashMap<>();
	}

	static void consumeMetric(Envelope env, Map<String, Usage> totals) {
		if (!"metric".equals(env.type())) {
			return;
		}
		// Map<String,Object> p = env.payload();
		// Map<String,Object> dims = (Map) p.getOrDefault("dims", Map.of());
		// String name = (String) p.get("name");
		// Object value = p.get("value");
		// if (!(value instanceof Number n)) return;
		// Usage u = totals.computeIfAbsent((String) dims.getOrDefault("tenant",
		// "unknown"), k -> new Usage());
		// switch (name) { case "tokens.used" -> ... ; case "cost.usd" -> ... ; default
		// -> {} }
		throw new UnsupportedOperationException("consumeMetric " + env);
	}

	public static void main(String[] args) {
		Map<String, ARCPClient> clients = new LinkedHashMap<>();
		Map<String, Profile> profiles = new HashMap<>();
		for (String name : PEERS) {
			ARCPClient c = null; // transport per peer URL, identity, auth elided
			// c.open();
			clients.put(name, c);
			// Marketplace fields ride on the negotiated capabilities; no extra
			// round trip to learn cost / latency / class.
			profiles.put(name, profileFrom(/* c.negotiatedCapabilities() */ null));
		}

		Map<String, Usage> totals = new HashMap<>();
		var execs = Executors.newVirtualThreadPerTaskExecutor();
		for (ARCPClient c : clients.values()) {
			execs.submit(() -> {
				// for (Envelope env : c.events()) consumeMetric(env, totals);
				throw new UnsupportedOperationException("meter on " + c);
			});
		}

		List<String> chain = candidateChain(profiles, "balanced");
		Envelope reply = invokeWithFallback(clients, chain, "chat.completion",
				Map.of("prompt", "Hello", "tenant", "acme-corp"),
				"trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
		// System.out.println("chosen=" +
		// reply.extensions().get("arcpx.market.peer.v1"));
		if (reply == null) {
			System.out.println("usage=" + totals);
		}

		execs.shutdownNow();
		// for (ARCPClient c : clients.values()) c.close();
	}
}
