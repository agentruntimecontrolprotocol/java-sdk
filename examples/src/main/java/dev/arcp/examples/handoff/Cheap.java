package dev.arcp.examples.handoff;

/** Cheap-tier inference. Real version: anthropic/litellm call. */
public final class Cheap {

	public record Attempt(String answer, double confidence) {
	}

	private Cheap() {
	}

	public static Attempt attempt(String prompt) {
		throw new UnsupportedOperationException("cheap attempt: " + prompt);
	}
}
