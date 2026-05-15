package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSubscribed(
        @JsonProperty("job_id") JobId jobId,
        @JsonProperty("current_status") String currentStatus,
        String agent,
        @Nullable Lease lease,
        @JsonProperty("parent_job_id") @Nullable JobId parentJobId,
        @JsonProperty("trace_id") @Nullable TraceId traceId,
        @JsonProperty("subscribed_from") long subscribedFrom,
        boolean replayed)
        implements Message {

    @JsonCreator
    public JobSubscribed(
            @JsonProperty("job_id") JobId jobId,
            @JsonProperty("current_status") String currentStatus,
            @JsonProperty("agent") String agent,
            @JsonProperty("lease") @Nullable Lease lease,
            @JsonProperty("parent_job_id") @Nullable JobId parentJobId,
            @JsonProperty("trace_id") @Nullable TraceId traceId,
            @JsonProperty("subscribed_from") long subscribedFrom,
            @JsonProperty("replayed") boolean replayed) {
        this.jobId = jobId;
        this.currentStatus = currentStatus;
        this.agent = agent;
        this.lease = lease;
        this.parentJobId = parentJobId;
        this.traceId = traceId;
        this.subscribedFrom = subscribedFrom;
        this.replayed = replayed;
    }

    @Override
    public Type kind() {
        return Type.JOB_SUBSCRIBED;
    }
}
