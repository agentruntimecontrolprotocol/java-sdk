package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobAccepted(
        @JsonProperty("job_id") JobId jobId,
        String agent,
        Lease lease,
        @JsonProperty("lease_constraints") @Nullable LeaseConstraints leaseConstraints,
        @Nullable Map<String, BigDecimal> budget,
        @JsonProperty("accepted_at") Instant acceptedAt,
        @JsonProperty("trace_id") @Nullable TraceId traceId)
        implements Message {

    @JsonCreator
    public JobAccepted(
            @JsonProperty("job_id") JobId jobId,
            @JsonProperty("agent") String agent,
            @JsonProperty("lease") Lease lease,
            @JsonProperty("lease_constraints") @Nullable LeaseConstraints leaseConstraints,
            @JsonProperty("budget") @Nullable Map<String, BigDecimal> budget,
            @JsonProperty("accepted_at") Instant acceptedAt,
            @JsonProperty("trace_id") @Nullable TraceId traceId) {
        this.jobId = jobId;
        this.agent = agent;
        this.lease = lease;
        this.leaseConstraints = leaseConstraints;
        this.budget = budget == null ? null : Map.copyOf(budget);
        this.acceptedAt = acceptedAt;
        this.traceId = traceId;
    }

    @Override
    public Type kind() {
        return Type.JOB_ACCEPTED;
    }
}
