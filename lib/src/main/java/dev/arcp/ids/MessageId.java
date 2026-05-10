package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.arcp.util.Ulid;
import java.util.Objects;

/** Envelope-level message id (RFC §6.1, §6.4 transport idempotency key). */
public record MessageId(String value) {

	public MessageId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank()) {
			throw new IllegalArgumentException("blank id");
		}
	}

	@JsonValue
	public String asString() {
		return value;
	}

	/** @return a fresh ULID-backed id. */
	public static MessageId random() {
		return new MessageId(Ulid.next());
	}

	/** Jackson factory. */
	@JsonCreator
	public static MessageId of(String s) {
		return new MessageId(s);
	}
}
