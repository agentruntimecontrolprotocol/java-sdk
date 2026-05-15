package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSubscribe(
        @JsonProperty("job_id") JobId jobId,
        @JsonProperty("from_event_seq") @Nullable Long fromEventSeq,
        @Nullable Boolean history)
        implements Message {

    @JsonCreator
    public JobSubscribe(
            @JsonProperty("job_id") JobId jobId,
            @JsonProperty("from_event_seq") @Nullable Long fromEventSeq,
            @JsonProperty("history") @Nullable Boolean history) {
        this.jobId = jobId;
        this.fromEventSeq = fromEventSeq;
        this.history = history;
    }

    @Override
    public Type kind() {
        return Type.JOB_SUBSCRIBE;
    }
}
