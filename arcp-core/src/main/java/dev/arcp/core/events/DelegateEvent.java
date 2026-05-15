package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.JobId;

public record DelegateEvent(
        @JsonProperty("child_job_id") JobId childJobId, String agent) implements EventBody {

    @JsonCreator
    public DelegateEvent(
            @JsonProperty("child_job_id") JobId childJobId,
            @JsonProperty("agent") String agent) {
        this.childJobId = childJobId;
        this.agent = agent;
    }

    @Override
    public Kind kind() {
        return Kind.DELEGATE;
    }
}
