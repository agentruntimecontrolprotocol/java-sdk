package dev.arcp.examples.cancellation;

import dev.arcp.client.ARCPClient;
import dev.arcp.envelope.Envelope;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import java.util.Map;

/** Two scenarios over the §10.4 / §10.5 control surface. */
public final class Main {

	private static final int CANCEL_DEADLINE_MS = 5_000;

	private Main() {
	}

	static String startLongJob(ARCPClient client) {
		// Envelope accepted = client.request(client.envelope("tool.invoke",
		// payload=Map.of(
		// "tool", "demo.long_running",
		// "arguments", Map.of("work_seconds", 600))), 10s);
		// return (String) accepted.payload().get("job_id");
		throw new UnsupportedOperationException("startLongJob on " + client);
	}

	/**
	 * Cooperative cancel. Runtime drives target to a clean checkpoint inside
	 * {@code deadlineMs} before terminating; escalates to {@code ABORTED} on
	 * timeout (RFC §10.4).
	 */
	static Envelope cancelJob(ARCPClient client, String jobId, String reason, int deadlineMs) {
		// Envelope reply = client.request(client.envelope("cancel", payload=Map.of(
		// "target", "job", "target_id", jobId, "reason", reason,
		// "deadline_ms", deadlineMs)), deadlineMs / 1000 + 5);
		// if ("cancel.refused".equals(reply.type()))
		// throw new ARCPException(FAILED_PRECONDITION, ...);
		// return reply;
		throw new UnsupportedOperationException(
				"cancel job=" + jobId + " reason=" + reason + " deadline=" + deadlineMs);
	}

	/**
	 * Distinct from cancel: pauses the job ({@code blocked}), runtime emits
	 * {@code human.input.request}. Job is NOT terminated (RFC §10.5).
	 */
	static void interruptJob(ARCPClient client, String jobId, String prompt) {
		// client.send(client.envelope("interrupt", payload=Map.of(
		// "target", "job", "target_id", jobId, "prompt", prompt)));
		if (Map.of(jobId, prompt).isEmpty()) {
			throw new UnsupportedOperationException("interrupt elided");
		}
	}

	static Envelope awaitTerminal(ARCPClient client, String jobId) {
		// for (Envelope env : client.events()) {
		// if (!jobId.equals(env.jobId())) continue;
		// if (TERMINAL.contains(env.type())) return env;
		// }
		throw new UnsupportedOperationException("awaitTerminal " + jobId);
	}

	static void scenarioCancel() {
		ARCPClient client = null; // transport, identity, auth elided
		// client.open();
		try {
			String jobId = startLongJob(client);
			Thread.sleep(2_000); // let the job actually start
			Envelope ack = cancelJob(client, jobId, "user_aborted", CANCEL_DEADLINE_MS);
			System.out.println("cancel ack: " + ack.type());
			Envelope terminal = awaitTerminal(client, jobId);
			System.out.println("terminal: " + terminal.type());
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		} finally {
			// client.close();
		}
	}

	static void scenarioInterrupt() {
		ARCPClient client = null;
		// client.open();
		try {
			String jobId = startLongJob(client);
			Thread.sleep(2_000);
			interruptJob(client, jobId, "Pause and ask before touching production tables.");
			// Runtime now emits human.input.request; answer via examples/human_input.
			// for (Envelope env : client.events()) {
			// if ("human.input.request".equals(env.type()) && jobId.equals(env.jobId())) {
			// System.out.println("awaiting human: " + env.payload().get("prompt"));
			// return;
			// }
			// }
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		} finally {
			// client.close();
		}
	}

	public static void main(String[] args) {
		String which = args.length > 0 ? args[0] : "cancel";
		switch (which) {
			case "cancel" -> scenarioCancel();
			case "interrupt" -> scenarioInterrupt();
			default -> throw new ARCPException(ErrorCode.INVALID_ARGUMENT, "unknown scenario: " + which);
		}
	}
}
