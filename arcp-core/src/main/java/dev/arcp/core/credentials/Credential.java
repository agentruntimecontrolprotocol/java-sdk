package dev.arcp.core.credentials;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Provisioned credential delivered in {@code job.accepted.payload.credentials} per §9.8.1. The
 * upstream service at {@code endpoint} is the enforcement boundary for the constraints baked into
 * the credential (§9.8.3).
 *
 * @param id stable identifier within the job, used for audit, rotation, and revocation
 * @param scheme the authentication scheme; {@code bearer} is the only scheme the spec defines
 * @param value the credential material; treat as a secret and never log
 * @param endpoint the base URL at which the credential is valid; agents MUST NOT present it to
 *     other URLs
 * @param profile vendor-neutral hint for the API protocol spoken at {@code endpoint} (e.g. {@code
 *     openai}), or {@code null}
 * @param constraints read-only echo of the lease restrictions the upstream enforces, or {@code
 *     null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Credential(
    CredentialId id,
    CredentialScheme scheme,
    String value,
    String endpoint,
    @Nullable String profile,
    @Nullable CredentialConstraints constraints) {
  /** Canonical constructor requiring the §9.8.1 mandatory fields. */
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
    return "Credential[id="
        + id
        + ", scheme="
        + scheme
        + ", endpoint="
        + endpoint
        + ", profile="
        + profile
        + ", value=REDACTED]";
  }
}
