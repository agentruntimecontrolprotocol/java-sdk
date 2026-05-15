package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ThoughtEvent(String text) implements EventBody {
    @JsonCreator
    public ThoughtEvent(@JsonProperty("text") String text) {
        this.text = text;
    }

    @Override
    public Kind kind() {
        return Kind.THOUGHT;
    }
}
