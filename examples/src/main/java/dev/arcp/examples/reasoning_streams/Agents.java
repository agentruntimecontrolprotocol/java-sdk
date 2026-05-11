package dev.arcp.examples.reasoning_streams;

import java.util.Map;

/** Primary + critic LLM stand-ins. */
public final class Agents {

	public enum Severity {
		NUDGE, WARN, HALT
	}

	public record Critique(Severity severity, String summary, String suggestion, int consumedTokens) {
	}

	private Agents() {
	}

	/**
	 * One reasoning step. Real version: an Anthropic call that folds the critique
	 * into the prompt.
	 */
	public static String primaryStep(String request, Map<String, Object> priorCritique) {
		throw new UnsupportedOperationException("primaryStep: " + request);
	}

	/** Critic LLM. Returns severity, summary, suggestion, tokens consumed. */
	public static Critique critiqueThought(String thought) {
		throw new UnsupportedOperationException("critiqueThought: " + thought);
	}
}
