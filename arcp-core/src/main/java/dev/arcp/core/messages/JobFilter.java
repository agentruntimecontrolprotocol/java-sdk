package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobFilter(
        @Nullable List<String> status,
        @Nullable String agent,
        @JsonProperty("created_after") @Nullable Instant createdAfter) {
    @JsonCreator
    public JobFilter(
            @JsonProperty("status") @Nullable List<String> status,
            @JsonProperty("agent") @Nullable String agent,
            @JsonProperty("created_after") @Nullable Instant createdAfter) {
        this.status = status == null ? null : List.copyOf(status);
        this.agent = agent;
        this.createdAfter = createdAfter;
    }

    public static JobFilter all() {
        return new JobFilter(null, null, null);
    }
}
