package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Capabilities;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * §6.2 {@code session.hello} payload: opens (or resumes, §6.3) a session, identifying the client,
 * presenting §6.1 authentication, and advertising capabilities for feature negotiation.
 *
 * @param client client identification
 * @param auth §6.1 authentication block
 * @param capabilities advertised client capabilities; defaults to JSON-only with no features
 * @param resumeToken §6.3 resume token from the prior welcome ({@code resume_token}), or {@code
 *     null} for a fresh session
 * @param lastEventSeq §6.3 last received event sequence ({@code last_event_seq}), or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionHello(
    ClientInfo client,
    Auth auth,
    Capabilities capabilities,
    @JsonProperty("resume_token") @Nullable String resumeToken,
    @JsonProperty("last_event_seq") @Nullable Long lastEventSeq)
    implements Message {

  /** Canonical constructor; a null {@code capabilities} becomes the empty JSON-only set. */
  @JsonCreator
  public SessionHello(
      @JsonProperty("client") ClientInfo client,
      @JsonProperty("auth") Auth auth,
      @JsonProperty("capabilities") @Nullable Capabilities capabilities,
      @JsonProperty("resume_token") @Nullable String resumeToken,
      @JsonProperty("last_event_seq") @Nullable Long lastEventSeq) {
    this.client = Objects.requireNonNull(client, "client");
    this.auth = Objects.requireNonNull(auth, "auth");
    this.capabilities = capabilities == null ? Capabilities.of(java.util.Set.of()) : capabilities;
    this.resumeToken = resumeToken;
    this.lastEventSeq = lastEventSeq;
  }

  @Override
  public Type kind() {
    return Type.SESSION_HELLO;
  }
}
