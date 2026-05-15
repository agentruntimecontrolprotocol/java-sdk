package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

public record TraceId(String value) {
    private static final SecureRandom RNG = new SecureRandom();

    public TraceId {
        Objects.requireNonNull(value, "value");
    }

    public static TraceId generate() {
        byte[] bytes = new byte[16];
        RNG.nextBytes(bytes);
        return new TraceId(HexFormat.of().formatHex(bytes));
    }

    @JsonCreator
    public static TraceId of(String value) {
        return new TraceId(value);
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
