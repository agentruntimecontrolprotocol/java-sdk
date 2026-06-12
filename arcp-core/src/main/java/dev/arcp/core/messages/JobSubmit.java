package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * §7.1 {@code job.submit} payload: requests execution of {@code input} by {@code agent}, with an
 * optional capability lease request, §9.5 constraints, §7.2 idempotency key, and runtime cap.
 *
 * @param agent the target agent, optionally version-pinned (§7.5)
 * @param input agent-defined input payload
 * @param leaseRequest requested capability lease ({@code lease_request}, §9.2), or {@code null}
 * @param leaseConstraints requested {@code lease_constraints} (§9.5), or {@code null} for no
 *     expiration
 * @param idempotencyKey §7.2 idempotency key ({@code idempotency_key}), or {@code null}
 * @param maxRuntimeSec runtime cap in seconds ({@code max_runtime_sec}); exceeding it ends the job
 *     with {@code TIMEOUT}; or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSubmit(
    AgentRef agent,
    JsonNode input,
    @JsonProperty("lease_request") @Nullable Lease leaseRequest,
    @JsonProperty("lease_constraints") @Nullable LeaseConstraints leaseConstraints,
    @JsonProperty("idempotency_key") @Nullable String idempotencyKey,
    @JsonProperty("max_runtime_sec") @Nullable Integer maxRuntimeSec)
    implements Message {

  /** Canonical constructor requiring {@code agent} and {@code input}. */
  @JsonCreator
  public JobSubmit(
      @JsonProperty("agent") AgentRef agent,
      @JsonProperty("input") JsonNode input,
      @JsonProperty("lease_request") @Nullable Lease leaseRequest,
      @JsonProperty("lease_constraints") @Nullable LeaseConstraints leaseConstraints,
      @JsonProperty("idempotency_key") @Nullable String idempotencyKey,
      @JsonProperty("max_runtime_sec") @Nullable Integer maxRuntimeSec) {
    this.agent = Objects.requireNonNull(agent, "agent");
    this.input = Objects.requireNonNull(input, "input");
    this.leaseRequest = leaseRequest;
    this.leaseConstraints = leaseConstraints;
    this.idempotencyKey = idempotencyKey;
    this.maxRuntimeSec = maxRuntimeSec;
  }

  @Override
  public Type kind() {
    return Type.JOB_SUBMIT;
  }
}
