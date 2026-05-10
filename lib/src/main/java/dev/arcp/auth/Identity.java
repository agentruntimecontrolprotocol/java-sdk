package dev.arcp.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Identity advertised in {@code session.accepted} (RFC §8.3): the runtime's
 * {@code kind}/{@code version}/{@code fingerprint} and the authenticated
 * principal's {@code trust_level}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Identity(@JsonProperty("kind") String kind, @JsonProperty("version") String version,
		@JsonProperty("fingerprint") @Nullable String fingerprint, @JsonProperty("trust_level") String trustLevel) {
}
