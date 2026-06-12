package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/**
 * §8.1 job.event payload: {@code { kind, ts, body }}. {@code body} is held generically; decode via
 * {@link dev.arcp.core.events.Events#decode}.
 *
 * @param eventKind the §8.2 event kind discriminator ({@code kind})
 * @param ts event timestamp ({@code ts})
 * @param body kind-specific body JSON
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobEvent(@JsonProperty("kind") String eventKind, Instant ts, JsonNode body)
    implements Message {

  /** Canonical constructor requiring all fields. */
  @JsonCreator
  public JobEvent(
      @JsonProperty("kind") String eventKind,
      @JsonProperty("ts") Instant ts,
      @JsonProperty("body") JsonNode body) {
    this.eventKind = Objects.requireNonNull(eventKind, "eventKind");
    this.ts = Objects.requireNonNull(ts, "ts");
    this.body = Objects.requireNonNull(body, "body");
  }

  @Override
  public Type kind() {
    return Type.JOB_EVENT;
  }
}
