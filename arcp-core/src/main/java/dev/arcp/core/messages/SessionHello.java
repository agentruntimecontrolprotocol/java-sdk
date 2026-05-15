package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Capabilities;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionHello(
        ClientInfo client,
        Auth auth,
        Capabilities capabilities,
        @JsonProperty("resume_token") @Nullable String resumeToken,
        @JsonProperty("last_event_seq") @Nullable Long lastEventSeq)
        implements Message {

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
