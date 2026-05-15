package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public record RuntimeInfo(String name, String version) {
    @JsonCreator
    public RuntimeInfo(
            @JsonProperty("name") String name, @JsonProperty("version") String version) {
        this.name = Objects.requireNonNull(name, "name");
        this.version = Objects.requireNonNull(version, "version");
    }
}
