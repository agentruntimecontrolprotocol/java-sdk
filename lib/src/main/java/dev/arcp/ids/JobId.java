package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.arcp.util.Ulid;
import java.util.Objects;

/** Job identifier (RFC §10). */
public record JobId(String value) {

	public JobId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank())
			throw new IllegalArgumentException("blank id");
	}

	@JsonValue
	public String asString() {
		return value;
	}

	public static JobId random() {
		return new JobId(Ulid.next());
	}

	@JsonCreator
	public static JobId of(String s) {
		return new JobId(s);
	}
}
