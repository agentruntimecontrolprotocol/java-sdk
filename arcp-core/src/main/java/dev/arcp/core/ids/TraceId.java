package dev.arcp.core.ids;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/**
 * §11 trace context, the envelope {@code trace_id} field carrying the W3C {@code traceparent} value
 * for the operation. Runtimes forward it to tool servers and sub-agents. Serialized as a bare JSON
 * string.
 *
 * @param value the trace context string
 */
public record TraceId(String value) {
  private static final SecureRandom RNG = new SecureRandom();

  /** Canonical constructor requiring a non-null value. */
  public TraceId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Generates a new trace id of 16 secure-random bytes, hex-encoded (32 characters).
   *
   * @return a fresh trace id
   */
  public static TraceId generate() {
    byte[] bytes = new byte[16];
    RNG.nextBytes(bytes);
    return new TraceId(HexFormat.of().formatHex(bytes));
  }

  /**
   * Wraps an existing trace context string.
   *
   * @param value the trace context string
   * @return the trace id
   */
  @JsonCreator
  public static TraceId of(String value) {
    return new TraceId(value);
  }

  /**
   * Returns the raw trace context string serialized on the wire.
   *
   * @return the trace context string
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
