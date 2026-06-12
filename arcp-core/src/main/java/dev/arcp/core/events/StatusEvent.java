package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * §8.2 {@code status} event body: a job phase transition. Phases include implementation-defined
 * signals such as {@code back_pressure} (§6.5) and {@code credential_rotated} (§9.8.2).
 *
 * @param phase the phase label
 * @param message human-readable detail, or {@code null}
 * @param details structured phase-specific payload (e.g. a §9.8.2 rotated credential body), or
 *     {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusEvent(String phase, @Nullable String message, @Nullable JsonNode details)
    implements EventBody {
  /**
   * Creates a status event without details.
   *
   * @param phase the phase label
   * @param message human-readable detail, or {@code null}
   */
  public StatusEvent(String phase, @Nullable String message) {
    this(phase, message, null);
  }

  /** Canonical constructor. */
  @JsonCreator
  public StatusEvent(
      @JsonProperty("phase") String phase,
      @JsonProperty("message") @Nullable String message,
      @JsonProperty("details") @Nullable JsonNode details) {
    this.phase = phase;
    this.message = message;
    this.details = details;
  }

  @Override
  public Kind kind() {
    return Kind.STATUS;
  }
}
