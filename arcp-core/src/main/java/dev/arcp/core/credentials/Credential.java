package dev.arcp.core.credentials;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Credential(
        CredentialId id,
        CredentialScheme scheme,
        String value,
        String endpoint,
        @Nullable String profile,
        @Nullable CredentialConstraints constraints) {
    @JsonCreator
    public Credential(
            @JsonProperty("id") CredentialId id,
            @JsonProperty("scheme") CredentialScheme scheme,
            @JsonProperty("value") String value,
            @JsonProperty("endpoint") String endpoint,
            @JsonProperty("profile") @Nullable String profile,
            @JsonProperty("constraints") @Nullable CredentialConstraints constraints) {
        this.id = Objects.requireNonNull(id, "id");
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.value = Objects.requireNonNull(value, "value");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.profile = profile;
        this.constraints = constraints;
    }

    /** §14: never log or expose the bearer secret through object rendering. */
    @Override
    public String toString() {
        return "Credential[id=" + id + ", scheme=" + scheme + ", endpoint=" + endpoint
                + ", profile=" + profile + ", value=REDACTED]";
    }
}
