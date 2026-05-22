package dev.arcp.core.credentials;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialConstraints(
        @JsonProperty("cost.budget") @Nullable List<String> costBudget,
        @JsonProperty("model.use") @Nullable List<String> modelUse,
        @JsonProperty("expires_at") @Nullable Instant expiresAt) {
    public CredentialConstraints {
        costBudget = costBudget == null ? null : List.copyOf(costBudget);
        modelUse = modelUse == null ? null : List.copyOf(modelUse);
    }
}
