package dev.arcp.messages.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.arcp.envelope.MessageType;
import org.jspecify.annotations.Nullable;

/** Liveness probe (RFC §6.2). Sender expects a {@link Pong} in response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Ping(@Nullable String nonce) implements MessageType {

	@Override
	public String type() {
		return "ping";
	}
}
