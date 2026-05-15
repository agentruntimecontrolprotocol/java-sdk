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

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSubmit(
        AgentRef agent,
        JsonNode input,
        @JsonProperty("lease_request") @Nullable Lease leaseRequest,
        @JsonProperty("lease_constraints") @Nullable LeaseConstraints leaseConstraints,
        @JsonProperty("idempotency_key") @Nullable String idempotencyKey,
        @JsonProperty("max_runtime_sec") @Nullable Integer maxRuntimeSec)
        implements Message {

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
