package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * §8.2.1 progress event. {@code current} MUST be ≥ 0. If {@code total} is present, {@code current}
 * SHOULD be ≤ {@code total}. Advisory only: the protocol does not act on progress events.
 *
 * @param current units of work completed so far; never negative
 * @param total expected total in the same units, or {@code null} when indeterminate
 * @param units label for the unit of work (e.g. {@code files}), or {@code null}
 * @param message human-readable progress note, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProgressEvent(
    long current, @Nullable Long total, @Nullable String units, @Nullable String message)
    implements EventBody {

  /**
   * Canonical constructor enforcing §8.2.1 bounds.
   *
   * @throws IllegalArgumentException if {@code current} or {@code total} is negative
   */
  @JsonCreator
  public ProgressEvent(
      @JsonProperty("current") long current,
      @JsonProperty("total") @Nullable Long total,
      @JsonProperty("units") @Nullable String units,
      @JsonProperty("message") @Nullable String message) {
    if (current < 0) {
      throw new IllegalArgumentException("current must be non-negative: " + current);
    }
    if (total != null && total < 0) {
      throw new IllegalArgumentException("total must be non-negative: " + total);
    }
    this.current = current;
    this.total = total;
    this.units = units;
    this.message = message;
  }

  @Override
  public Kind kind() {
    return Kind.PROGRESS;
  }
}
