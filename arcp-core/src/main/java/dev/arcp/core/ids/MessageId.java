package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Objects;

public record MessageId(String value) {
    public MessageId {
        Objects.requireNonNull(value, "value");
    }

    public static MessageId generate() {
        return new MessageId(UlidCreator.getMonotonicUlid().toString());
    }

    @JsonCreator
    public static MessageId of(String value) {
        return new MessageId(value);
    }

    @JsonValue
    public String asString() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
