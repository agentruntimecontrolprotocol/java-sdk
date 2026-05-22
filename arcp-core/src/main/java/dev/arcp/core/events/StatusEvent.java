package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusEvent(String phase, @Nullable String message, @Nullable JsonNode details)
        implements EventBody {
    public StatusEvent(String phase, @Nullable String message) {
        this(phase, message, null);
    }

    @JsonCreator
    public StatusEvent(
            @JsonProperty("phase") String phase,
            @JsonProperty("message") @Nullable String message,
            @JsonProperty("details") @Nullable JsonNode details) {
        this.phase = phase;
        this.message = message;
        this.details = details;
    }

    @Override
    public Kind kind() {
        return Kind.STATUS;
    }
}
