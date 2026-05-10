package dev.arcp.messages.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.arcp.envelope.MessageType;
import org.jspecify.annotations.Nullable;

/**
 * Liveness response (RFC §6.2). Echoes the {@link Ping#nonce()} when present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pong(@Nullable String nonce) implements MessageType {

	@Override
	public String type() {
		return "pong";
	}
}
