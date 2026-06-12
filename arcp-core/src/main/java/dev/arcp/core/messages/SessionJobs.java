package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.MessageId;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * §6.6 {@code session.jobs} payload: a page of job summaries answering a {@code session.list_jobs}
 * request.
 *
 * @param requestId envelope id of the originating {@code session.list_jobs} ({@code request_id})
 * @param jobs the job summaries on this page
 * @param nextCursor opaque cursor for the next page ({@code next_cursor}), or {@code null} when the
 *     listing is exhausted
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionJobs(
    @JsonProperty("request_id") MessageId requestId,
    List<JobSummary> jobs,
    @JsonProperty("next_cursor") @Nullable String nextCursor)
    implements Message {

  /** Canonical constructor; {@code jobs} is defensively copied. */
  @JsonCreator
  public SessionJobs(
      @JsonProperty("request_id") MessageId requestId,
      @JsonProperty("jobs") List<JobSummary> jobs,
      @JsonProperty("next_cursor") @Nullable String nextCursor) {
    this.requestId = requestId;
    this.jobs = List.copyOf(jobs);
    this.nextCursor = nextCursor;
  }

  @Override
  public Type kind() {
    return Type.SESSION_JOBS;
  }
}
