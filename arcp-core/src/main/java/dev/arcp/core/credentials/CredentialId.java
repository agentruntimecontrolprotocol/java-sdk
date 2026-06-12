package dev.arcp.core.credentials;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * Identifier of a §9.8 provisioned credential (wire example {@code cred_01J...}), serialized as a
 * bare JSON string. Stable within the job; correlates {@code credential_rotated} status events and
 * revocation with the credential they affect (§9.8.2).
 *
 * @param value the identifier string
 */
public record CredentialId(@JsonValue String value) {
  /** Canonical constructor requiring a non-null value. */
  public CredentialId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Creates a credential id from its string form.
   *
   * @param v the identifier string
   * @return the credential id
   */
  public static CredentialId of(String v) {
    return new CredentialId(v);
  }

  /**
   * Jackson factory deserializing a credential id from its JSON string form.
   *
   * @param v the identifier string
   * @return the credential id
   */
  @JsonCreator
  public static CredentialId fromJson(String v) {
    return new CredentialId(v);
  }

  @Override
  public String toString() {
    return value;
  }
}
