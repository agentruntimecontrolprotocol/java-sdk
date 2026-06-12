package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * §6.7 graceful-close acknowledgement. The runtime sends {@code session.closed} in response to a
 * {@code session.close} request before tearing the session down.
 *
 * @param reason human-readable close reason, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionClosed(@Nullable String reason) implements Message {
  /** Canonical constructor. */
  @JsonCreator
  public SessionClosed(@JsonProperty("reason") @Nullable String reason) {
    this.reason = reason;
  }

  @Override
  public Type kind() {
    return Type.SESSION_CLOSED;
  }
}
