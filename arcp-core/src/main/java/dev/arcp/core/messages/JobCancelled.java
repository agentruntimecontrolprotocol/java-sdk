package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * §7.4 cancel acknowledgement. The runtime sends {@code job.cancelled} (carrying the job id in the
 * envelope) to acknowledge a {@code job.cancel}, followed by a terminal {@code job.error} with code
 * {@code CANCELLED}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobCancelled(@Nullable String reason) implements Message {
  @JsonCreator
  public JobCancelled(@JsonProperty("reason") @Nullable String reason) {
    this.reason = reason;
  }

  @Override
  public Type kind() {
    return Type.JOB_CANCELLED;
  }
}
