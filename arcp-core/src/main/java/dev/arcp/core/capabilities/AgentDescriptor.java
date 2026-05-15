package dev.arcp.core.capabilities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDescriptor(
        String name,
        List<String> versions,
        @Nullable @JsonProperty("default") String defaultVersion) {

    @JsonCreator
    public AgentDescriptor(
            @JsonProperty("name") String name,
            @JsonProperty("versions") @Nullable List<String> versions,
            @JsonProperty("default") @Nullable String defaultVersion) {
        this.name = Objects.requireNonNull(name, "name");
        this.versions = versions == null ? List.of() : List.copyOf(versions);
        this.defaultVersion = defaultVersion;
    }
}
