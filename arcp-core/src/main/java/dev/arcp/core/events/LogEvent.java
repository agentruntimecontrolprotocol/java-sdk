package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * §8.2 {@code log} event body: a line of diagnostic output from the job.
 *
 * @param level log severity label (e.g. {@code info}, {@code warn})
 * @param message the log message
 */
public record LogEvent(String level, String message) implements EventBody {
  /** Canonical constructor. */
  @JsonCreator
  public LogEvent(@JsonProperty("level") String level, @JsonProperty("message") String message) {
    this.level = level;
    this.message = message;
  }

  @Override
  public Kind kind() {
    return Kind.LOG;
  }
}
