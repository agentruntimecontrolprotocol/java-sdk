package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.capabilities.Capabilities;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * §6.2 {@code session.welcome} payload: the runtime's handshake response carrying resume
 * parameters, the heartbeat interval, and acknowledged capabilities. The effective feature set is
 * the intersection of hello and welcome features.
 *
 * @param runtime runtime identification
 * @param resumeToken §6.3 resume token, rotated on every successful welcome ({@code resume_token}),
 *     or {@code null}
 * @param resumeWindowSec seconds the event buffer is retained for resume ({@code
 *     resume_window_sec}), or {@code null}
 * @param heartbeatIntervalSec §6.4 heartbeat interval ({@code heartbeat_interval_sec}), or {@code
 *     null} when heartbeats are not negotiated
 * @param capabilities runtime capabilities, including the §7.5 agent inventory
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionWelcome(
    RuntimeInfo runtime,
    @JsonProperty("resume_token") @Nullable String resumeToken,
    @JsonProperty("resume_window_sec") @Nullable Integer resumeWindowSec,
    @JsonProperty("heartbeat_interval_sec") @Nullable Integer heartbeatIntervalSec,
    Capabilities capabilities)
    implements Message {

  /** Canonical constructor requiring {@code runtime} and {@code capabilities}. */
  @JsonCreator
  public SessionWelcome(
      @JsonProperty("runtime") RuntimeInfo runtime,
      @JsonProperty("resume_token") @Nullable String resumeToken,
      @JsonProperty("resume_window_sec") @Nullable Integer resumeWindowSec,
      @JsonProperty("heartbeat_interval_sec") @Nullable Integer heartbeatIntervalSec,
      @JsonProperty("capabilities") Capabilities capabilities) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.resumeToken = resumeToken;
    this.resumeWindowSec = resumeWindowSec;
    this.heartbeatIntervalSec = heartbeatIntervalSec;
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
  }

  @Override
  public Type kind() {
    return Type.SESSION_WELCOME;
  }
}
