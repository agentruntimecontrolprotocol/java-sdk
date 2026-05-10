package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * Logical idempotency key (RFC §6.4). Caller-supplied; never randomly
 * generated.
 */
public record IdempotencyKey(String value) {

	public IdempotencyKey {
		Objects.requireNonNull(value, "value");
		if (value.isBlank())
			throw new IllegalArgumentException("blank key");
	}

	@JsonValue
	public String asString() {
		return value;
	}

	@JsonCreator
	public static IdempotencyKey of(String s) {
		return new IdempotencyKey(s);
	}
}
