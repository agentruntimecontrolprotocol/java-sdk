package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

/**
 * §6.4 {@code session.pong} payload: prompt reply to a {@code session.ping}.
 *
 * @param pingNonce nonce of the ping being answered ({@code ping_nonce})
 * @param receivedAt time the ping was received ({@code received_at})
 */
public record SessionPong(
    @JsonProperty("ping_nonce") String pingNonce, @JsonProperty("received_at") Instant receivedAt)
    implements Message {
  /** Canonical constructor requiring both fields. */
  @JsonCreator
  public SessionPong(
      @JsonProperty("ping_nonce") String pingNonce,
      @JsonProperty("received_at") Instant receivedAt) {
    this.pingNonce = Objects.requireNonNull(pingNonce, "pingNonce");
    this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
  }

  @Override
  public Type kind() {
    return Type.SESSION_PONG;
  }
}
