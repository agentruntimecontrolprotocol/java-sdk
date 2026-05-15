package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArtifactRefEvent(
        String uri,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("byte_size") @Nullable Long byteSize,
        @Nullable String sha256)
        implements EventBody {

    @JsonCreator
    public ArtifactRefEvent(
            @JsonProperty("uri") String uri,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("byte_size") @Nullable Long byteSize,
            @JsonProperty("sha256") @Nullable String sha256) {
        this.uri = uri;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.sha256 = sha256;
    }

    @Override
    public Kind kind() {
        return Kind.ARTIFACT_REF;
    }
}
