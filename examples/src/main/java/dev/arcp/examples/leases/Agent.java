package dev.arcp.examples.leases;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/** Stand-in for an Anthropic tool-use loop. */
public final class Agent {

	public record ToolCall(List<String> argv, String reason) {
	}

	public record LLMStep(String thought, Optional<ToolCall> toolCall, Optional<String> finalAnswer) {
	}

	private Agent() {
	}

	public static Iterator<LLMStep> llmLoop(String userRequest) {
		// Real version: anthropic.AsyncAnthropic with system prompt, yielding
		// one LLMStep per turn.
		throw new UnsupportedOperationException("llmLoop: " + userRequest);
	}
}
