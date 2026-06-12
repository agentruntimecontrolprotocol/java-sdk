package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * §6.5 {@code session.ack} payload: advisory notice of the client's highest processed event
 * sequence, letting the runtime free buffered events early and detect slow consumers. Resume still
 * requires {@code last_event_seq} independently.
 *
 * @param lastProcessedSeq highest event sequence the client has processed ({@code
 *     last_processed_seq})
 */
public record SessionAck(@JsonProperty("last_processed_seq") long lastProcessedSeq)
    implements Message {
  /** Canonical constructor. */
  @JsonCreator
  public SessionAck {}

  @Override
  public Type kind() {
    return Type.SESSION_ACK;
  }
}
