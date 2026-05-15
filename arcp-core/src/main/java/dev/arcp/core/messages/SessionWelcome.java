package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.capabilities.Capabilities;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionWelcome(
        RuntimeInfo runtime,
        @JsonProperty("resume_token") @Nullable String resumeToken,
        @JsonProperty("resume_window_sec") @Nullable Integer resumeWindowSec,
        @JsonProperty("heartbeat_interval_sec") @Nullable Integer heartbeatIntervalSec,
        Capabilities capabilities)
        implements Message {

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
