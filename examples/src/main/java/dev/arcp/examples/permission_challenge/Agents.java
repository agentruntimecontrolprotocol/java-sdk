package dev.arcp.examples.permission_challenge;

import dev.arcp.envelope.Envelope;
import java.util.Optional;

/** Generator + reviewer stand-ins. */
public final class Agents {

	public record Patch(String diff) {
	}

	public record ReviewVerdict(boolean grant, String reason) {
	}

	private Agents() {
	}

	public static Patch propose(String ticket, Optional<String> priorDenial) {
		throw new UnsupportedOperationException("propose: " + ticket);
	}

	public static ReviewVerdict review(String ticket, Envelope request) {
		// Reviewer parses the patch out of `request.payload["resource"]`
		// or by looking it up by fingerprint, then runs the LLM.
		throw new UnsupportedOperationException("review: " + ticket);
	}
}
