package dev.arcp.envelope;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Envelope priority (RFC §6.5). Ordering:
 * {@code low < normal < high < critical}.
 *
 * <p>
 * {@code critical} is reserved for permission requests blocking real human
 * action and terminal job events. Runtimes SHOULD reorder between streams but
 * MUST NEVER reorder within a {@code stream_id}.
 */
public enum Priority {
	LOW, NORMAL, HIGH, CRITICAL;

	/** @return canonical lowercase wire form. */
	@JsonValue
	public String wire() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	@JsonCreator
	public static Priority fromWire(String s) {
		return Priority.valueOf(s.toUpperCase(java.util.Locale.ROOT));
	}
}
