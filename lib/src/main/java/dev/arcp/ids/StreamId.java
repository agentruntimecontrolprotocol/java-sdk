package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.arcp.util.Ulid;
import java.util.Objects;

/** Stream identifier (RFC §11). */
public record StreamId(String value) {

	public StreamId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank())
			throw new IllegalArgumentException("blank id");
	}

	@JsonValue
	public String asString() {
		return value;
	}

	public static StreamId random() {
		return new StreamId(Ulid.next());
	}

	@JsonCreator
	public static StreamId of(String s) {
		return new StreamId(s);
	}
}
