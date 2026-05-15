package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionListJobs(
        @Nullable JobFilter filter,
        @Nullable Integer limit,
        @Nullable String cursor)
        implements Message {
    @JsonCreator
    public SessionListJobs(
            @JsonProperty("filter") @Nullable JobFilter filter,
            @JsonProperty("limit") @Nullable Integer limit,
            @JsonProperty("cursor") @Nullable String cursor) {
        this.filter = filter;
        this.limit = limit;
        this.cursor = cursor;
    }

    @Override
    public Type kind() {
        return Type.SESSION_LIST_JOBS;
    }
}
