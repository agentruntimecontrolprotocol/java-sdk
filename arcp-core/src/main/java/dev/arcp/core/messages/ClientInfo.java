package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public record ClientInfo(String name, String version) {
    @JsonCreator
    public ClientInfo(
            @JsonProperty("name") String name, @JsonProperty("version") String version) {
        this.name = Objects.requireNonNull(name, "name");
        this.version = Objects.requireNonNull(version, "version");
    }
}
