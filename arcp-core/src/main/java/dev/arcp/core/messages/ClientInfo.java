package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Client identification carried in {@code session.hello.payload.client} (§6.2).
 *
 * @param name client implementation name (e.g. {@code examplectl})
 * @param version client implementation version
 */
public record ClientInfo(String name, String version) {
  /** Canonical constructor requiring both fields. */
  @JsonCreator
  public ClientInfo(@JsonProperty("name") String name, @JsonProperty("version") String version) {
    this.name = Objects.requireNonNull(name, "name");
    this.version = Objects.requireNonNull(version, "version");
  }
}
