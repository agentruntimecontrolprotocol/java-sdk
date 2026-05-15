package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Objects;

public record SessionId(String value) {
    public SessionId {
        Objects.requireNonNull(value, "value");
    }

    public static SessionId generate() {
        return new SessionId("sess_" + UlidCreator.getMonotonicUlid());
    }

    @JsonCreator
    public static SessionId of(String value) {
        return new SessionId(value);
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
