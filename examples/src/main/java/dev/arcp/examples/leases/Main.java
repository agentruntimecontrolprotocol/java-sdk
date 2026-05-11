package dev.arcp.examples.leases;

import dev.arcp.client.ARCPClient;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import dev.arcp.examples.leases.Agent.LLMStep;
import dev.arcp.examples.leases.Agent.ToolCall;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Sandboxed on-call agent. Lease-gated shell, reasoning streamed. */
public final class Main {

	private static final Set<String> READ_BINARIES = Set.of("/usr/bin/journalctl", "/usr/bin/cat", "/usr/bin/ss",
			"/usr/bin/ps");
	private static final Set<String> WRITE_BINARIES = Set.of("/usr/bin/systemctl", "/usr/bin/kill");
	private static final int READ_LEASE_SECONDS = 30 * 60;
	private static final int WRITE_LEASE_SECONDS = 60;

	private Main() {
	}

	record Classification(String permission, String resource, String operation, int leaseSeconds) {
	}

	static Classification classify(List<String> argv, String host) {
		String binary = argv.get(0);
		if (READ_BINARIES.contains(binary)) {
			return new Classification("host.read", "host:" + host, "read", READ_LEASE_SECONDS);
		}
		if (WRITE_BINARIES.contains(binary)) {
			String target = "/usr/bin/systemctl".equals(binary) ? argv.get(2) : argv.get(1);
			return new Classification("host.write", "host:" + host + "/" + binary + "/" + target, "write",
					WRITE_LEASE_SECONDS);
		}
		throw new ARCPException(ErrorCode.PERMISSION_DENIED, "binary not allowed: " + binary);
	}

	static String acquireLease(ARCPClient client, String permission, String resource, String operation, int seconds,
			String reason) {
		// reply = client.request(client.envelope("permission.request", payload={
		// "permission": permission, "resource": resource, "operation": operation,
		// "reason": reason, "requested_lease_seconds": seconds}), timeout=120s)
		// if reply.type == "permission.deny": throw PERMISSION_DENIED
		// return reply.payload["lease_id"]
		throw new UnsupportedOperationException("permission.request " + permission + " on " + resource + " op="
				+ operation + " seconds=" + seconds + " reason=" + reason);
	}

	static String runCommand(ARCPClient client, List<String> argv, String reason, String host) {
		Classification c = classify(argv, host);
		String lease = acquireLease(client, c.permission, c.resource, c.operation, c.leaseSeconds, reason);
		// The lease is the only guard. Spawn the subprocess elsewhere.
		return "<would run " + argv + " under lease " + lease + ">";
	}

	static void emitThought(ARCPClient client, String streamId, int sequence, String text) {
		// client.send(client.envelope("stream.chunk", stream_id=streamId, payload={
		// "sequence": sequence, "kind": "thought",
		// "role": "assistant_thought", "content": text}))
		throw new UnsupportedOperationException("emit thought seq=" + sequence + " text=" + text);
	}

	public static void main(String[] args) {
		ARCPClient client = null; // transport, identity (constrained), auth elided
		// client.open();

		String streamId = "str_" + Instant.now().getEpochSecond();
		// client.send(client.envelope("stream.open", stream_id=streamId,
		// payload={"kind": "thought"}));

		int seq = 0;
		Iterator<LLMStep> steps = Agent.llmLoop("api-gateway pod is OOMing every 4 minutes");
		while (steps.hasNext()) {
			LLMStep step = steps.next();
			emitThought(client, streamId, seq++, step.thought());
			if (step.toolCall().isPresent()) {
				ToolCall tc = step.toolCall().get();
				try {
					runCommand(client, tc.argv(), tc.reason(), "edge-pod-04");
				} catch (ARCPException ex) {
					if (ex.code() != ErrorCode.PERMISSION_DENIED) {
						throw ex;
					}
					// PERMISSION_DENIED feeds back into the next prompt
				}
			}
			if (step.finalAnswer().isPresent()) {
				System.out.println(step.finalAnswer().get());
				break;
			}
		}
		// client.close();
	}
}
