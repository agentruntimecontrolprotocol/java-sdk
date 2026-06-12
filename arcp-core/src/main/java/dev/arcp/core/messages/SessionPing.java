package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

/**
 * §6.4 {@code session.ping} payload: heartbeat probe from an idle peer. The receiver must answer
 * promptly with {@code session.pong}. Heartbeats are not included in {@code event_seq}.
 *
 * @param nonce opaque value echoed back as the pong's {@code ping_nonce}
 * @param sentAt send timestamp ({@code sent_at})
 */
public record SessionPing(String nonce, @JsonProperty("sent_at") Instant sentAt)
    implements Message {
  /** Canonical constructor requiring both fields. */
  @JsonCreator
  public SessionPing(@JsonProperty("nonce") String nonce, @JsonProperty("sent_at") Instant sentAt) {
    this.nonce = Objects.requireNonNull(nonce, "nonce");
    this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
  }

  @Override
  public Type kind() {
    return Type.SESSION_PING;
  }
}
