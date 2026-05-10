package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.arcp.util.Ulid;
import java.io.Serializable;
import java.util.Objects;

/** Lease identifier (RFC §15.5). */
public record LeaseId(String value) implements Serializable {

	public LeaseId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank())
			throw new IllegalArgumentException("blank id");
	}

	@JsonValue
	public String asString() {
		return value;
	}

	public static LeaseId random() {
		return new LeaseId(Ulid.next());
	}

	@JsonCreator
	public static LeaseId of(String s) {
		return new LeaseId(s);
	}
}
