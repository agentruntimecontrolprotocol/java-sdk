package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record LogEvent(String level, String message) implements EventBody {
    @JsonCreator
    public LogEvent(
            @JsonProperty("level") String level, @JsonProperty("message") String message) {
        this.level = level;
        this.message = message;
    }

    @Override
    public Kind kind() {
        return Kind.LOG;
    }
}
