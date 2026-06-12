package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * §7.4 {@code job.cancel} payload: requests cancellation of a non-terminal job identified by the
 * envelope's {@code job_id}. Cancellation is reserved for the session that submitted the job.
 *
 * @param reason human-readable cancellation reason, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobCancel(@Nullable String reason) implements Message {
  /** Canonical constructor. */
  @JsonCreator
  public JobCancel(@JsonProperty("reason") @Nullable String reason) {
    this.reason = reason;
  }

  @Override
  public Type kind() {
    return Type.JOB_CANCEL;
  }
}
