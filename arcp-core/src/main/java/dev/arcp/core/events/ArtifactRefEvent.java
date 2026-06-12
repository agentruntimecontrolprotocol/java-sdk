package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * §8.2 {@code artifact_ref} event body: a reference to an artifact produced by the job, to be
 * fetched out of band.
 *
 * @param uri location of the artifact
 * @param contentType MIME type of the artifact ({@code content_type})
 * @param byteSize artifact size in bytes ({@code byte_size}), or {@code null} if unknown
 * @param sha256 hex SHA-256 digest of the artifact bytes, or {@code null} if not computed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArtifactRefEvent(
    String uri,
    @JsonProperty("content_type") String contentType,
    @JsonProperty("byte_size") @Nullable Long byteSize,
    @Nullable String sha256)
    implements EventBody {

  /** Canonical constructor. */
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
