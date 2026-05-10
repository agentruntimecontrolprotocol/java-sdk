package dev.arcp.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

/**
 * Sealed root of credential payloads carried in {@code session.open} and
 * {@code session.authenticate} (RFC §8.2). v0.1 ships
 * {@link BearerCredentials}, {@link JwtCredentials}, and
 * {@link NoneCredentials}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "scheme", visible = true)
@JsonSubTypes({@JsonSubTypes.Type(value = Credentials.BearerCredentials.class, name = "bearer"),
		@JsonSubTypes.Type(value = Credentials.JwtCredentials.class, name = "signed_jwt"),
		@JsonSubTypes.Type(value = Credentials.NoneCredentials.class, name = "none")})
public sealed interface Credentials {

	/** @return the canonical scheme name (RFC §8.2). */
	String scheme();

	/** Cleartext bearer token (§8.2). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record BearerCredentials(@JsonProperty("scheme") String scheme,
			@JsonProperty("token") String token) implements Credentials {
		public BearerCredentials(String token) {
			this("bearer", token);
		}
	}

	/** Signed JWT (§8.2). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record JwtCredentials(@JsonProperty("scheme") String scheme,
			@JsonProperty("jwt") String jwt) implements Credentials {
		public JwtCredentials(String jwt) {
			this("signed_jwt", jwt);
		}
	}

	/**
	 * Anonymous (§8.2). Only honored when the {@code anonymous} capability is true.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record NoneCredentials(@JsonProperty("scheme") String scheme,
			@JsonProperty("subject") @Nullable String subject) implements Credentials {
		public NoneCredentials() {
			this("none", null);
		}
	}
}
