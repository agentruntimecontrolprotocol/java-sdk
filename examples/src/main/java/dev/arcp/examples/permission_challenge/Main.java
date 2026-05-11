package dev.arcp.examples.permission_challenge;

import dev.arcp.client.ARCPClient;
import dev.arcp.envelope.Envelope;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import dev.arcp.examples.permission_challenge.Agents.Patch;
import dev.arcp.examples.permission_challenge.Agents.ReviewVerdict;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Generator proposes; reviewer holds veto via permission.request. */
public final class Main {

	private static final int MAX_REVISIONS = 4;

	private Main() {
	}

	static String fingerprint(String diff) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(diff.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Generator: ask for a {@code repo.write} lease scoped to this exact diff. */
	static String requestApply(ARCPClient client, String ticketId, Patch patch) {
		String fp = fingerprint(patch.diff());
		// reply = client.request(client.envelope("permission.request",
		// idempotency_key="review:" + ticketId + ":" + fp, // dedupes identical patches
		// payload={"permission": "repo.write",
		// "resource": "ticket:" + ticketId + "/" + fp,
		// "operation": "apply_patch",
		// "reason": "apply patch",
		// "requested_lease_seconds": 90}), timeout=300s)
		// if reply.type == "permission.deny": throw new
		// ARCPException(PERMISSION_DENIED, ...)
		// return reply.payload["lease_id"]
		throw new UnsupportedOperationException("permission.request " + ticketId + "/" + fp);
	}

	/** Reviewer: grant or typed deny. */
	static void respond(ARCPClient client, Envelope request, ReviewVerdict verdict) {
		if (verdict.grant()) {
			// client.send(client.envelope("permission.grant",
			// correlation_id=request.id,
			// payload={"permission": ..., "resource": ..., "operation": ...,
			// "lease_seconds": 90}));
			throw new UnsupportedOperationException("permission.grant for " + request.id());
		} else {
			// client.send(client.envelope("permission.deny",
			// correlation_id=request.id,
			// payload={"permission": ..., "reason": verdict.reason(),
			// "code": ErrorCode.FAILED_PRECONDITION.toString()}));
			throw new UnsupportedOperationException("permission.deny for " + request.id() + ": " + verdict.reason());
		}
	}

	static void reviewerLoop(ARCPClient reviewer, String ticket) {
		// for (Envelope env : reviewer.events()) {
		// if ("permission.request".equals(env.type())) {
		// ReviewVerdict v = Agents.review(ticket, env);
		// respond(reviewer, env, v);
		// }
		// }
		throw new UnsupportedOperationException("reviewerLoop for " + ticket);
	}

	public static void main(String[] args) {
		// Two sessions, one per agent. In production they'd be in different
		// processes on different runtimes; the message contract is identical.
		ARCPClient generator = null; // transport, identity, auth elided
		ARCPClient reviewer = null;
		// generator.open(); reviewer.open();

		String ticketId = "JIRA-4812";
		String ticket = "Reject JWTs whose `aud` does not match the configured audience. Add a unit test.";

		var execs = Executors.newVirtualThreadPerTaskExecutor();
		Future<?> revTask = execs.submit(() -> reviewerLoop(reviewer, ticket));

		Optional<String> priorDenial = Optional.empty();
		try {
			for (int i = 0; i < MAX_REVISIONS; i++) {
				Patch patch = Agents.propose(ticket, priorDenial);
				try {
					String lease = requestApply(generator, ticketId, patch);
					System.out.println("applied " + fingerprint(patch.diff()) + " lease=" + lease);
					return;
				} catch (ARCPException ex) {
					if (ex.code() != ErrorCode.PERMISSION_DENIED) {
						throw ex;
					}
					priorDenial = Optional.of(ex.getMessage());
				}
			}
			System.out.println("abandoned after max_revisions");
		} finally {
			revTask.cancel(true);
			execs.shutdownNow();
			// generator.close(); reviewer.close();
		}
	}
}
