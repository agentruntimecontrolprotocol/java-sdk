package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Objects;

/**
 * Unique identifier of one wire envelope, the §5 {@code id} field. Serialized as a bare JSON
 * string.
 *
 * @param value the identifier string
 */
public record MessageId(String value) {
  /** Canonical constructor requiring a non-null value. */
  public MessageId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Generates a new monotonic ULID identifier.
   *
   * @return a fresh message id
   */
  public static MessageId generate() {
    return new MessageId(UlidCreator.getMonotonicUlid().toString());
  }

  /**
   * Wraps an existing identifier string.
   *
   * @param value the identifier string
   * @return the message id
   */
  @JsonCreator
  public static MessageId of(String value) {
    return new MessageId(value);
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
