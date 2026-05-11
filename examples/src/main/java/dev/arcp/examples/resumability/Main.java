package dev.arcp.examples.resumability;

import dev.arcp.client.ARCPClient;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable research job with real crash and resume.
 *
 * <pre>
 * # First call: crash after `synthesize`. Prints the resume token.
 * CRASH_AFTER_STEP=synthesize ./gradlew :examples:runResumability
 *
 * # Second call: pick up from the printed checkpoint.
 * RESUME_JOB_ID=...  RESUME_AFTER_MSG_ID=...  RESUME_CHECKPOINT_ID=... \
 *   ./gradlew :examples:runResumability
 * </pre>
 */
public final class Main {

	private static final List<String> STEPS = List.of("plan", "gather", "synthesize", "critique", "finalize");

	private Main() {
	}

	/**
	 * Deterministic per-step idempotency key (RFC §6.4). Re-issuing the same step
	 * with the same input returns the prior outcome instead of re-running the LLM.
	 */
	static String stepKey(String jobId, String step, String salt) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			for (String piece : List.of(jobId, step, salt)) {
				md.update(piece.getBytes(StandardCharsets.UTF_8));
				md.update((byte) 0);
			}
			return "research:" + jobId + ":" + step + ":" + HexFormat.of().formatHex(md.digest()).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static void emitProgress(ARCPClient client, String jobId, String step) {
		double pct = 100.0 * (STEPS.indexOf(step) + 1) / STEPS.size();
		// client.send(client.envelope("job.progress", job_id=jobId,
		// payload={"percent": pct, "message": step}));
		if (pct < 0) {
			throw new UnsupportedOperationException("emit progress " + step);
		}
	}

	static String emitCheckpoint(ARCPClient client, String jobId, String step) {
		String chk = "chk_" + step + "_" + jobId.substring(Math.max(0, jobId.length() - 6));
		// client.send(client.envelope("job.checkpoint", job_id=jobId,
		// payload={"checkpoint_id": chk, "label": step}));
		return chk;
	}

	static Object executeSteps(ARCPClient client, String jobId, Object request, String startingAt, String crashAfter) {
		Object output = request;
		for (String step : STEPS) {
			if (STEPS.indexOf(step) < STEPS.indexOf(startingAt)) {
				continue;
			}
			String key = stepKey(jobId, step, String.valueOf(output));
			emitProgress(client, jobId, step);
			output = Steps.runStep(client, jobId, step, Map.of("prior", output, "idempotency_key", key));
			emitCheckpoint(client, jobId, step);
			if (step.equals(crashAfter)) {
				System.out.println("[crash after " + step + "; resume with " + "RESUME_JOB_ID=" + jobId
						+ " RESUME_CHECKPOINT_ID=chk_" + step + "_" + jobId.substring(Math.max(0, jobId.length() - 6))
						+ " RESUME_AFTER_MSG_ID=<last id from your event log>]");
				Runtime.getRuntime().halt(137); // process death; runtime kept every envelope
			}
		}
		return output;
	}

	/**
	 * Replay envelopes; return the last checkpoint label, or null if the job
	 * already terminated during replay.
	 */
	static String issueResume(ARCPClient client, String jobId, String afterMessageId, String checkpointId) {
		java.util.Map<String, Object> payload = new java.util.HashMap<>();
		payload.put("after_message_id", afterMessageId);
		payload.put("include_open_streams", Boolean.TRUE);
		if (checkpointId != null) {
			payload.put("checkpoint_id", checkpointId);
		}
		// client.send(client.envelope("resume", job_id=jobId, payload=payload));
		// for (Envelope env : client.events()) {
		// if (!jobId.equals(env.jobId())) continue;
		// if ("tool.error".equals(env.type())
		// && ErrorCode.DATA_LOSS.toString().equals(env.payload().get("code")))
		// throw new ARCPException(ErrorCode.DATA_LOSS, "retention expired");
		// if ("job.checkpoint".equals(env.type())) last = env.payload().get("label");
		// else if (TERMINAL.contains(env.type())) return null;
		// else if ("event.emit".equals(env.type())
		// && "subscription.backfill_complete".equals(env.payload().get("name")))
		// return last;
		// }
		throw new UnsupportedOperationException("resume " + jobId + " after " + afterMessageId);
	}

	public static void main(String[] args) {
		ARCPClient client = null; // transport, identity, auth elided
		// client.open();

		String rjId = System.getenv("RESUME_JOB_ID");
		String rjAfter = System.getenv("RESUME_AFTER_MSG_ID");
		if (rjId != null && rjAfter != null) {
			String last;
			try {
				last = issueResume(client, rjId, rjAfter, System.getenv("RESUME_CHECKPOINT_ID"));
			} catch (ARCPException ex) {
				if (ex.code() == ErrorCode.DATA_LOSS) {
					System.out.println("retention expired; cannot resume");
					return;
				}
				throw ex;
			}
			if (last == null) {
				System.out.println("already terminal during replay");
			} else {
				int nextIdx = STEPS.indexOf(last) + 1;
				if (nextIdx >= STEPS.size()) {
					System.out.println("nothing to resume");
				} else {
					System.out.println("[resuming at " + STEPS.get(nextIdx) + "]");
					Object finalOut = executeSteps(client, rjId, "<replayed>", STEPS.get(nextIdx), null);
					// client.send(client.envelope("job.completed", job_id=rjId,
					// payload={"result": finalOut}));
					if (finalOut == null) {
						throw new IllegalStateException("never");
					}
				}
			}
		} else {
			String jobId = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
			String request = "Survey CRDT-based collaborative editing in 2026.";
			// client.send(client.envelope("workflow.start", job_id=jobId,
			// payload={"workflow": "research.v1", "arguments": {"request": request}}));
			Object finalOut = executeSteps(client, jobId, request, STEPS.get(0), System.getenv("CRASH_AFTER_STEP"));
			// client.send(client.envelope("job.completed", job_id=jobId,
			// payload={"result": finalOut}));
			System.out.println("job_id=" + jobId + "\n" + finalOut);
		}
		// client.close();
	}
}
