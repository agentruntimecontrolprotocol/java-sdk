package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Objects;

/**
 * Identifier of a §8.4 streamed result, the {@code result_id} field (wire example {@code
 * res_01J...}). Correlates {@code result_chunk} events with the terminating {@code job.result}.
 * Serialized as a bare JSON string.
 *
 * @param value the identifier string
 */
public record ResultId(String value) {
  /** Canonical constructor requiring a non-null value. */
  public ResultId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Generates a new {@code res_}-prefixed monotonic ULID identifier.
   *
   * @return a fresh result id
   */
  public static ResultId generate() {
    return new ResultId("res_" + UlidCreator.getMonotonicUlid());
  }

  /**
   * Wraps an existing identifier string.
   *
   * @param value the identifier string
   * @return the result id
   */
  @JsonCreator
  public static ResultId of(String value) {
    return new ResultId(value);
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
