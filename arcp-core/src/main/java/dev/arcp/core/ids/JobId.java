package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Objects;

public record JobId(String value) {
    public JobId {
        Objects.requireNonNull(value, "value");
    }

    public static JobId generate() {
        return new JobId("job_" + UlidCreator.getMonotonicUlid());
    }

    @JsonCreator
    public static JobId of(String value) {
        return new JobId(value);
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
