package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Objects;

/**
 * Identifier of a session (§6), the envelope {@code session_id} field (wire example {@code
 * sess_01J...}). Serialized as a bare JSON string.
 *
 * @param value the identifier string
 */
public record SessionId(String value) {
  /** Canonical constructor requiring a non-null value. */
  public SessionId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Generates a new {@code sess_}-prefixed monotonic ULID identifier.
   *
   * @return a fresh session id
   */
  public static SessionId generate() {
    return new SessionId("sess_" + UlidCreator.getMonotonicUlid());
  }

  /**
   * Wraps an existing identifier string.
   *
   * @param value the identifier string
   * @return the session id
   */
  @JsonCreator
  public static SessionId of(String value) {
    return new SessionId(value);
  }

  /**
   * Returns the raw identifier string serialized on the wire.
   *
   * @return the identifier string
   */
  @JsonValue
  public String asString() {
    return value;
  }

  @Override
  public String toString() {
    return value;
  }
}
