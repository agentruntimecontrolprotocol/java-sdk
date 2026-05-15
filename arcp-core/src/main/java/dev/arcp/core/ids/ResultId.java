package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Objects;

public record ResultId(String value) {
    public ResultId {
        Objects.requireNonNull(value, "value");
    }

    public static ResultId generate() {
        return new ResultId("res_" + UlidCreator.getMonotonicUlid());
    }

    @JsonCreator
    public static ResultId of(String value) {
        return new ResultId(value);
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
