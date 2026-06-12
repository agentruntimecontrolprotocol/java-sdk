package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One entry of a §6.6 {@code session.jobs} listing.
 *
 * @param jobId the job id ({@code job_id})
 * @param agent resolved agent as {@code name@version}
 * @param status current job status (e.g. {@code running})
 * @param lease the job's effective lease, or {@code null}
 * @param parentJobId parent job for §10 delegated jobs ({@code parent_job_id}), or {@code null}
 * @param createdAt creation timestamp ({@code created_at})
 * @param traceId §11 trace context ({@code trace_id}), or {@code null}
 * @param lastEventSeq sequence of the job's most recent event ({@code last_event_seq}), or {@code
 *     null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSummary(
    @JsonProperty("job_id") JobId jobId,
    String agent,
    String status,
    @Nullable Lease lease,
    @JsonProperty("parent_job_id") @Nullable JobId parentJobId,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("trace_id") @Nullable TraceId traceId,
    @JsonProperty("last_event_seq") @Nullable Long lastEventSeq) {

  /** Canonical constructor. */
  @JsonCreator
  public JobSummary(
      @JsonProperty("job_id") JobId jobId,
      @JsonProperty("agent") String agent,
      @JsonProperty("status") String status,
      @JsonProperty("lease") @Nullable Lease lease,
      @JsonProperty("parent_job_id") @Nullable JobId parentJobId,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("trace_id") @Nullable TraceId traceId,
      @JsonProperty("last_event_seq") @Nullable Long lastEventSeq) {
    this.jobId = jobId;
    this.agent = agent;
    this.status = status;
    this.lease = lease;
    this.parentJobId = parentJobId;
    this.createdAt = createdAt;
    this.traceId = traceId;
    this.lastEventSeq = lastEventSeq;
  }
}
