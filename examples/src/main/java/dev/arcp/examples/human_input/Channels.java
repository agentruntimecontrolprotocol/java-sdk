package dev.arcp.examples.human_input;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Per-destination channel adapters. Real versions wrap ntfy.sh, SES, and the
 * Slack web API. Each returns a value matching the request's response_schema.
 */
public final class Channels {

	@FunctionalInterface
	public interface ChannelResponse extends BiFunction<String, Map<String, Object>, Map<String, Object>> {
	}

	public static final Map<String, ChannelResponse> REGISTRY = new LinkedHashMap<>();

	static {
		REGISTRY.put("ntfy:phone", Channels::ntfyPhone);
		REGISTRY.put("email:oncall", Channels::emailOncall);
		REGISTRY.put("slack:ops", Channels::slackOps);
	}

	private Channels() {
	}

	static Map<String, Object> ntfyPhone(String prompt, Map<String, Object> schema) {
		throw new UnsupportedOperationException("ntfy:phone " + prompt);
	}

	static Map<String, Object> emailOncall(String prompt, Map<String, Object> schema) {
		throw new UnsupportedOperationException("email:oncall " + prompt);
	}

	static Map<String, Object> slackOps(String prompt, Map<String, Object> schema) {
		throw new UnsupportedOperationException("slack:ops " + prompt);
	}
}
