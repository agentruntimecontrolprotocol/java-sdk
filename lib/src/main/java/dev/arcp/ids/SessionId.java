package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.arcp.util.Ulid;
import java.util.Objects;

/** Session identifier (RFC §8.1, §9). */
public record SessionId(String value) {

	public SessionId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank())
			throw new IllegalArgumentException("blank id");
	}

	@JsonValue
	public String asString() {
		return value;
	}

	public static SessionId random() {
		return new SessionId(Ulid.next());
	}

	@JsonCreator
	public static SessionId of(String s) {
		return new SessionId(s);
	}
}
