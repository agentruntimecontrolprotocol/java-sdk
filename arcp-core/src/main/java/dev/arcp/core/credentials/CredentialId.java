package dev.arcp.core.credentials;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

public record CredentialId(@JsonValue String value) {
    public CredentialId {
        Objects.requireNonNull(value, "value");
    }

    public static CredentialId of(String v) {
        return new CredentialId(v);
    }

    @JsonCreator
    public static CredentialId fromJson(String v) {
        return new CredentialId(v);
    }

    @Override
    public String toString() {
        return value;
    }
}
