package dev.arcp.core.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * ARCP wire envelope per spec §5.
 *
 * <p>Required fields: {@code arcp}, {@code id}, {@code type}, {@code payload}. Conditional fields:
 * {@code session_id}, {@code trace_id}, {@code job_id}, {@code event_seq}. Unknown top-level fields
 * MUST be ignored.
 *
 * @param arcp the {@code arcp} protocol version string; {@link #VERSION} for this SDK
 * @param id unique message id ({@code id})
 * @param type wire message type (e.g. {@code job.submit})
 * @param sessionId session correlation id ({@code session_id}), or {@code null}
 * @param traceId §11 trace context ({@code trace_id}), or {@code null}
 * @param jobId job correlation id ({@code job_id}), or {@code null}
 * @param eventSeq §8.3 session-scoped event sequence ({@code event_seq}), or {@code null}
 * @param payload type-specific payload object
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope(
    String arcp,
    MessageId id,
    String type,
    @JsonProperty("session_id") @Nullable SessionId sessionId,
    @JsonProperty("trace_id") @Nullable TraceId traceId,
    @JsonProperty("job_id") @Nullable JobId jobId,
    @JsonProperty("event_seq") @Nullable Long eventSeq,
    JsonNode payload) {

  /** The {@code arcp} protocol version string ({@code "1.1"}) stamped by {@link Builder#build}. */
  public static final String VERSION = "1.1";

  /** Canonical constructor requiring the §5 mandatory fields. */
  public Envelope {
    Objects.requireNonNull(arcp, "arcp");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
  }

  /**
   * Jackson factory deserializing an envelope from its §5 wire fields.
   *
   * @param arcp the {@code arcp} protocol version string
   * @param id unique message id
   * @param type wire message type
   * @param sessionId session correlation id, or {@code null}
   * @param traceId trace context, or {@code null}
   * @param jobId job correlation id, or {@code null}
   * @param eventSeq event sequence, or {@code null}
   * @param payload type-specific payload object
   * @return the envelope
   */
  @JsonCreator
  public static Envelope create(
      @JsonProperty("arcp") String arcp,
      @JsonProperty("id") MessageId id,
      @JsonProperty("type") String type,
      @JsonProperty("session_id") @Nullable SessionId sessionId,
      @JsonProperty("trace_id") @Nullable TraceId traceId,
      @JsonProperty("job_id") @Nullable JobId jobId,
      @JsonProperty("event_seq") @Nullable Long eventSeq,
      @JsonProperty("payload") JsonNode payload) {
    return new Envelope(arcp, id, type, sessionId, traceId, jobId, eventSeq, payload);
  }

  /**
   * Starts a builder for an envelope of the given type with a generated message id.
   *
   * @param type wire message type (e.g. {@code session.hello})
   * @return a new builder
   */
  public static Builder builder(String type) {
    return new Builder(type);
  }

  /** Fluent builder assembling an {@link Envelope} stamped with {@link Envelope#VERSION}. */
  public static final class Builder {
    private MessageId id = MessageId.generate();
    private final String type;
    private @Nullable SessionId sessionId;
    private @Nullable TraceId traceId;
    private @Nullable JobId jobId;
    private @Nullable Long eventSeq;
    private @Nullable JsonNode payload;

    Builder(String type) {
      this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Overrides the generated message id.
     *
     * @param id the message id to use
     * @return this builder
     */
    public Builder id(MessageId id) {
      this.id = id;
      return this;
    }

    /**
     * Sets the {@code session_id} field.
     *
     * @param sessionId the session id, or {@code null} to omit
     * @return this builder
     */
    public Builder sessionId(@Nullable SessionId sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    /**
     * Sets the {@code trace_id} field (§11).
     *
     * @param traceId the trace context, or {@code null} to omit
     * @return this builder
     */
    public Builder traceId(@Nullable TraceId traceId) {
      this.traceId = traceId;
      return this;
    }

    /**
     * Sets the {@code job_id} field.
     *
     * @param jobId the job id, or {@code null} to omit
     * @return this builder
     */
    public Builder jobId(@Nullable JobId jobId) {
      this.jobId = jobId;
      return this;
    }

    /**
     * Sets the {@code event_seq} field (§8.3).
     *
     * @param eventSeq the event sequence, or {@code null} to omit
     * @return this builder
     */
    public Builder eventSeq(@Nullable Long eventSeq) {
      this.eventSeq = eventSeq;
      return this;
    }

    /**
     * Sets the required payload object.
     *
     * @param payload the type-specific payload
     * @return this builder
     */
    public Builder payload(JsonNode payload) {
      this.payload = payload;
      return this;
    }

    /**
     * Builds the envelope with {@code arcp} set to {@link Envelope#VERSION}.
     *
     * @return the envelope
     * @throws NullPointerException if no payload was set
     */
    public Envelope build() {
      Objects.requireNonNull(payload, "payload");
      return new Envelope(VERSION, id, type, sessionId, traceId, jobId, eventSeq, payload);
    }
  }
}
