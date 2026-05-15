package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusEvent(String phase, @Nullable String message) implements EventBody {
    @JsonCreator
    public StatusEvent(
            @JsonProperty("phase") String phase,
            @JsonProperty("message") @Nullable String message) {
        this.phase = phase;
        this.message = message;
    }

    @Override
    public Kind kind() {
        return Kind.STATUS;
    }
}
