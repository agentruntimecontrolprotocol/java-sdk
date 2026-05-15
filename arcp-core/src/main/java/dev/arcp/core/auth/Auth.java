package dev.arcp.core.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * §6.1 authentication block. v1 supports {@code bearer} and {@code anonymous}.
 */
public record Auth(String scheme, @Nullable String token) {

    public static final String BEARER = "bearer";
    public static final String ANONYMOUS = "anonymous";

    @JsonCreator
    public Auth(
            @JsonProperty("scheme") String scheme,
            @JsonProperty("token") @Nullable String token) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.token = token;
    }

    public static Auth bearer(String token) {
        return new Auth(BEARER, Objects.requireNonNull(token, "token"));
    }

    public static Auth anonymous() {
        return new Auth(ANONYMOUS, null);
    }
}
