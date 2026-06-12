package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * §7.1 {@code job.accepted} payload: the runtime's acceptance of a {@code job.submit}, echoing the
 * effective lease and constraints plus initial budget counters and any §9.8 provisioned
 * credentials.
 *
 * @param jobId the assigned job id ({@code job_id})
 * @param agent the resolved agent as {@code name@version} (§7.5)
 * @param lease the effective lease granted to the job
 * @param leaseConstraints the effective {@code lease_constraints} (§9.5), or {@code null} when the
 *     lease has no expiration
 * @param budget initial budget counters per currency when {@code cost.budget} is leased (§9.6), or
 *     {@code null}
 * @param credentials §9.8 provisioned credentials, or {@code null} when none are issued
 * @param acceptedAt acceptance timestamp ({@code accepted_at})
 * @param traceId §11 trace context ({@code trace_id}), or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobAccepted(
    @JsonProperty("job_id") JobId jobId,
    String agent,
    Lease lease,
    @JsonProperty("lease_constraints") @Nullable LeaseConstraints leaseConstraints,
    @Nullable Map<String, BigDecimal> budget,
    @JsonProperty("credentials") @Nullable List<Credential> credentials,
    @JsonProperty("accepted_at") Instant acceptedAt,
    @JsonProperty("trace_id") @Nullable TraceId traceId)
    implements Message {

  /** Canonical constructor; {@code budget} and {@code credentials} are defensively copied. */
  @JsonCreator
  public JobAccepted(
      @JsonProperty("job_id") JobId jobId,
      @JsonProperty("agent") String agent,
      @JsonProperty("lease") Lease lease,
      @JsonProperty("lease_constraints") @Nullable LeaseConstraints leaseConstraints,
      @JsonProperty("budget") @Nullable Map<String, BigDecimal> budget,
      @JsonProperty("credentials") @Nullable List<Credential> credentials,
      @JsonProperty("accepted_at") Instant acceptedAt,
      @JsonProperty("trace_id") @Nullable TraceId traceId) {
    this.jobId = jobId;
    this.agent = agent;
    this.lease = lease;
    this.leaseConstraints = leaseConstraints;
    this.budget = budget == null ? null : Map.copyOf(budget);
    this.credentials = credentials == null ? null : List.copyOf(credentials);
    this.acceptedAt = acceptedAt;
    this.traceId = traceId;
  }

  @Override
  public Type kind() {
    return Type.JOB_ACCEPTED;
  }
}
