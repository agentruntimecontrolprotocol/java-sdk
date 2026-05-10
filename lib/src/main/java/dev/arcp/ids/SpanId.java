package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.arcp.util.Ulid;
import java.util.Objects;

/** Span identifier (RFC §17.1). */
public record SpanId(String value) {

	public SpanId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank())
			throw new IllegalArgumentException("blank id");
	}

	@JsonValue
	public String asString() {
		return value;
	}

	public static SpanId random() {
		return new SpanId(Ulid.next());
	}

	@JsonCreator
	public static SpanId of(String s) {
		return new SpanId(s);
	}
}
