package dev.arcp.core.capabilities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Agent inventory entry advertised in {@code session.welcome} capabilities (§6.2). Lists the
 * versions registered for one agent name and, optionally, the version that a bare {@code name}
 * submission resolves to per §7.5.
 *
 * @param name the agent name
 * @param versions the registered versions, possibly empty
 * @param defaultVersion the wire {@code default} version used for unversioned submissions, or
 *     {@code null} when the runtime advertises none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDescriptor(
    String name, List<String> versions, @Nullable @JsonProperty("default") String defaultVersion) {

  /** Canonical constructor; a null {@code versions} list becomes an empty list. */
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
