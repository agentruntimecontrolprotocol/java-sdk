package dev.arcp.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.arcp.util.Ulid;
import java.util.Objects;

/** Subscription identifier (RFC §13). */
public record SubscriptionId(String value) {

	public SubscriptionId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank())
			throw new IllegalArgumentException("blank id");
	}

	@JsonValue
	public String asString() {
		return value;
	}

	public static SubscriptionId random() {
		return new SubscriptionId(Ulid.next());
	}

	@JsonCreator
	public static SubscriptionId of(String s) {
		return new SubscriptionId(s);
	}
}
