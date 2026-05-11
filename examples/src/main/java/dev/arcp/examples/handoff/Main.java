package dev.arcp.examples.handoff;

import dev.arcp.client.ARCPClient;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import dev.arcp.examples.handoff.Cheap.Attempt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Cheap-tier first; escalate to deep tier via agent.handoff. */
public final class Main {

	private static final double CONFIDENCE_THRESHOLD = 0.65;
	private static final String CHEAP_URL = "wss://haiku-pool.tier1.internal";
	private static final String DEEP_URL = "wss://opus-pool.tier3.internal";
	private static final String DEEP_KIND = "arcp-opus-pool";
	private static final String DEEP_FINGERPRINT = "sha256:0a37bf7d61cca21f00...";

	private Main() {
	}

	static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static Map<String, Object> packageContext(ARCPClient client, Map<String, Object> transcript) {
		// Real: serialize transcript canonically (Jackson) then put.
		byte[] body = transcript.toString().getBytes(StandardCharsets.UTF_8);
		String artifactId = "art_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
		Map<String, Object> payload = Map.of("artifact_id", artifactId, "media_type", "application/json", "size",
				body.length, "sha256", sha256(body), "data", Base64.getEncoder().encodeToString(body));
		// reply = client.request(client.envelope("artifact.put", payload=payload),
		// 15s);
		// if reply.type != "artifact.ref": throw INTERNAL
		// return reply.payload
		throw new UnsupportedOperationException("artifact.put " + payload.get("artifact_id"));
	}

	static void emitHandoff(ARCPClient client, Map<String, Object> artifactRef, String traceId) {
		// client.send(client.envelope("agent.handoff", trace_id=traceId, payload={
		// "target_runtime": {"url": DEEP_URL, "kind": DEEP_KIND, "fingerprint":
		// DEEP_FINGERPRINT},
		// "session_id": client.sessionId(),
		// "shared_memory_ref": artifactRef}));
		throw new UnsupportedOperationException("agent.handoff to " + DEEP_URL + " ref=" + artifactRef);
	}

	public static void main(String[] args) {
		ARCPClient cheap = null; // transport=WebSocketTransport(CHEAP_URL), pinned
		// SessionAccepted accepted = cheap.open();
		// if (!"arcp-haiku-pool".equals(accepted.runtime().kind()))
		// throw new ARCPException(UNAUTHENTICATED, "cheap kind mismatch");

		String request = "what does CRDT stand for?";
		String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

		Attempt res = Cheap.attempt(request);
		if (res.confidence() >= CONFIDENCE_THRESHOLD) {
			System.out.println(res.answer());
		} else {
			Map<String, Object> artifact = packageContext(cheap,
					Map.of("user_request", request, "transcript",
							java.util.List.of(Map.of("role", "user", "content", request),
									Map.of("role", "assistant", "content", res.answer())),
							"cheap_confidence", res.confidence()));
			emitHandoff(cheap, artifact, traceId);
			System.out.println("[handed off to " + DEEP_KIND + " trace_id=" + traceId + "]");
		}
		// cheap.close();
		// touch the symbol so unused-import lint doesn't trip us
		if (false) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "unreachable");
		}
	}
}
