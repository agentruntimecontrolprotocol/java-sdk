package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;

/**
 * §7.6 {@code job.unsubscribe} payload: cancels a previous subscription to a job's event stream.
 *
 * @param jobId the job to detach from ({@code job_id})
 */
public record JobUnsubscribe(@JsonProperty("job_id") JobId jobId) implements Message {
  /** Canonical constructor. */
  @JsonCreator
  public JobUnsubscribe(@JsonProperty("job_id") JobId jobId) {
    this.jobId = jobId;
  }

  @Override
  public Type kind() {
    return Type.JOB_UNSUBSCRIBE;
  }
}
