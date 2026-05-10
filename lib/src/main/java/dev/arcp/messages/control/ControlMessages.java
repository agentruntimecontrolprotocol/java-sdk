package dev.arcp.messages.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.envelope.MessageType;
import dev.arcp.error.ErrorCode;
import dev.arcp.ids.MessageId;
import org.jspecify.annotations.Nullable;

/**
 * Concrete records for the control-plane message family (RFC §6.2, §10.4,
 * §11.2).
 *
 * <p>
 * {@link Ping} and {@link Pong} live in their own files for backward
 * compatibility with the Phase 1 surface; new control records ({@link Ack},
 * {@link Nack}, etc.) are nested here.
 */
public final class ControlMessages {

	static {
		MessageType.register("ping", Ping.class);
		MessageType.register("pong", Pong.class);
		MessageType.register("ack", Ack.class);
		MessageType.register("nack", Nack.class);
	}

	private ControlMessages() {
	}

	/** Force class init so the static block runs. */
	public static void load() {
	}

	/** Acknowledgement of a prior command (RFC §6.3). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Ack(@JsonProperty("of") MessageId of) implements MessageType {
		@Override
		public String type() {
			return "ack";
		}
	}

	/** Negative acknowledgement carrying a canonical error code (RFC §6.3, §18). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Nack(@JsonProperty("of") MessageId of, @JsonProperty("code") ErrorCode code,
			@JsonProperty("message") String message,
			@JsonProperty("details") @Nullable JsonNode details) implements MessageType {
		@Override
		public String type() {
			return "nack";
		}
	}
}
