package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.credentials.CredentialId;

/**
 * Body of the §9.8.2 {@code status} event with {@code phase: "credential_rotated"}: a provisioned
 * credential was re-issued mid-job. The prior {@code value} is revoked promptly.
 *
 * @param id id of the rotated credential
 * @param value the replacement credential material; treat as a secret
 */
public record CredentialRotatedBody(CredentialId id, String value) {
  /** Canonical constructor. */
  @JsonCreator
  public CredentialRotatedBody(
      @JsonProperty("id") CredentialId id, @JsonProperty("value") String value) {
    this.id = id;
    this.value = value;
  }
}
