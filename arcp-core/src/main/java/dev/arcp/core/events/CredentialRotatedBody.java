package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.credentials.CredentialId;

public record CredentialRotatedBody(CredentialId id, String value) {
  @JsonCreator
  public CredentialRotatedBody(
      @JsonProperty("id") CredentialId id, @JsonProperty("value") String value) {
    this.id = id;
    this.value = value;
  }
}
