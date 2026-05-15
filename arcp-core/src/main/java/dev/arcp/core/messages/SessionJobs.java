package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.MessageId;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionJobs(
        @JsonProperty("request_id") MessageId requestId,
        List<JobSummary> jobs,
        @JsonProperty("next_cursor") @Nullable String nextCursor)
        implements Message {

    @JsonCreator
    public SessionJobs(
            @JsonProperty("request_id") MessageId requestId,
            @JsonProperty("jobs") List<JobSummary> jobs,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {
        this.requestId = requestId;
        this.jobs = List.copyOf(jobs);
        this.nextCursor = nextCursor;
    }

    @Override
    public Type kind() {
        return Type.SESSION_JOBS;
    }
}
