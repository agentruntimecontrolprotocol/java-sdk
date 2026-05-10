package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.arcp.util.Ulid;
import java.util.Objects;

/** Artifact identifier (RFC §16). */
public record ArtifactId(String value) {

	public ArtifactId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank())
			throw new IllegalArgumentException("blank id");
	}

	@JsonValue
	public String asString() {
		return value;
	}

	public static ArtifactId random() {
		return new ArtifactId(Ulid.next());
	}

	@JsonCreator
	public static ArtifactId of(String s) {
		return new ArtifactId(s);
	}
}
