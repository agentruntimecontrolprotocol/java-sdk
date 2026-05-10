package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.arcp.util.Ulid;
import java.util.Objects;

/** Trace identifier (RFC §17.1). */
public record TraceId(String value) {

	public TraceId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank())
			throw new IllegalArgumentException("blank id");
	}

	@JsonValue
	public String asString() {
		return value;
	}

	public static TraceId random() {
		return new TraceId(Ulid.next());
	}

	@JsonCreator
	public static TraceId of(String s) {
		return new TraceId(s);
	}
}
