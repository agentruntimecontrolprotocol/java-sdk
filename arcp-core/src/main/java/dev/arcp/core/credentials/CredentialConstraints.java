package dev.arcp.core.credentials;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Read-only echo of the lease restrictions baked into a §9.8.1 provisioned credential.
 * Informational only: the upstream is the enforcer, and absence of a constraint here does not imply
 * the lease lacks it (§9.8.3).
 *
 * @param costBudget {@code cost.budget} entries (e.g. {@code USD:5.00}) mapped to the upstream
 *     spend cap, or {@code null}
 * @param modelUse {@code model.use} patterns mapped to the upstream allowed-model list, or {@code
 *     null}
 * @param expiresAt credential TTL mirroring {@code lease_constraints.expires_at}, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialConstraints(
    @JsonProperty("cost.budget") @Nullable List<String> costBudget,
    @JsonProperty("model.use") @Nullable List<String> modelUse,
    @JsonProperty("expires_at") @Nullable Instant expiresAt) {
  /** Canonical constructor; list components are defensively copied. */
  public CredentialConstraints {
    costBudget = costBudget == null ? null : List.copyOf(costBudget);
    modelUse = modelUse == null ? null : List.copyOf(modelUse);
  }
}
