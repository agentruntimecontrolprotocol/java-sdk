package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;

public record JobUnsubscribe(@JsonProperty("job_id") JobId jobId) implements Message {
    @JsonCreator
    public JobUnsubscribe(@JsonProperty("job_id") JobId jobId) {
        this.jobId = jobId;
    }

    @Override
    public Type kind() {
        return Type.JOB_UNSUBSCRIBE;
    }
}
