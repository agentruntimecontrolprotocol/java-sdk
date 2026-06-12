package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Objects;

/**
 * Identifier of a job, the envelope {@code job_id} field (wire example {@code job_01JABC...}).
 * Serialized as a bare JSON string.
 *
 * @param value the identifier string
 */
public record JobId(String value) {
  /** Canonical constructor requiring a non-null value. */
  public JobId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Generates a new {@code job_}-prefixed monotonic ULID identifier.
   *
   * @return a fresh job id
   */
  public static JobId generate() {
    return new JobId("job_" + UlidCreator.getMonotonicUlid());
  }

  /**
   * Wraps an existing identifier string.
   *
   * @param value the identifier string
   * @return the job id
   */
  @JsonCreator
  public static JobId of(String value) {
    return new JobId(value);
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
