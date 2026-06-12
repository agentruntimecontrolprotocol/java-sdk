package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;
import org.jspecify.annotations.Nullable;

/**
 * §7.6 {@code job.subscribe} payload: attaches the session to a job's live event stream, optionally
 * replaying buffered history. Subscription grants observation only — no cancel authority.
 *
 * @param jobId the job to attach to ({@code job_id})
 * @param fromEventSeq with {@code history=true}, replay buffered events with {@code seq >
 *     from_event_seq} before live streaming ({@code from_event_seq}); {@code null} means live-only
 * @param history whether to replay buffered history; {@code null} defaults to {@code false}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSubscribe(
    @JsonProperty("job_id") JobId jobId,
    @JsonProperty("from_event_seq") @Nullable Long fromEventSeq,
    @Nullable Boolean history)
    implements Message {

  /** Canonical constructor. */
  @JsonCreator
  public JobSubscribe(
      @JsonProperty("job_id") JobId jobId,
      @JsonProperty("from_event_seq") @Nullable Long fromEventSeq,
      @JsonProperty("history") @Nullable Boolean history) {
    this.jobId = jobId;
    this.fromEventSeq = fromEventSeq;
    this.history = history;
  }

  @Override
  public Type kind() {
    return Type.JOB_SUBSCRIBE;
  }
}
