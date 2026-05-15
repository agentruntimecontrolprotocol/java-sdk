package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

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
