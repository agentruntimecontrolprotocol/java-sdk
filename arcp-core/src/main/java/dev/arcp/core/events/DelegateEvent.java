package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;

/**
 * §10 {@code delegate} event body: the agent delegated a subset of its lease to a sub-agent running
 * as a child job, bounded by §9.4 subsetting rules.
 *
 * @param childJobId id of the spawned child job ({@code child_job_id})
 * @param agent agent reference executing the child job
 */
public record DelegateEvent(@JsonProperty("child_job_id") JobId childJobId, String agent)
    implements EventBody {

  /** Canonical constructor. */
  @JsonCreator
  public DelegateEvent(
      @JsonProperty("child_job_id") JobId childJobId, @JsonProperty("agent") String agent) {
    this.childJobId = childJobId;
    this.agent = agent;
  }

  @Override
  public Kind kind() {
    return Kind.DELEGATE;
  }
}
