package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * §6.7 graceful-close request, written on the wire as {@code session.close} ({@code session.bye}
 * remains a deprecated decode alias). In-flight jobs continue running and remain resumable within
 * the resume window.
 *
 * @param reason human-readable close reason, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionBye(@Nullable String reason) implements Message {
  /** Canonical constructor. */
  @JsonCreator
  public SessionBye(@JsonProperty("reason") @Nullable String reason) {
    this.reason = reason;
  }

  @Override
  public Type kind() {
    return Type.SESSION_BYE;
  }
}
