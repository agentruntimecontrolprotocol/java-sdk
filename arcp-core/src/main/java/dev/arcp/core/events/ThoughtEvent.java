package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * §8.2 {@code thought} event body: a fragment of the agent's reasoning trace.
 *
 * @param text the reasoning text
 */
public record ThoughtEvent(String text) implements EventBody {
  /** Canonical constructor. */
  @JsonCreator
  public ThoughtEvent(@JsonProperty("text") String text) {
    this.text = text;
  }

  @Override
  public Kind kind() {
    return Kind.THOUGHT;
  }
}
