package dev.arcp.messages.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.auth.Credentials;
import dev.arcp.auth.Identity;
import dev.arcp.capability.Capabilities;
import dev.arcp.envelope.MessageType;
import dev.arcp.error.ErrorCode;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Concrete records for the session-handshake message family (RFC §8.1, §8.3,
 * §8.4, §8.5, §9). Grouped here to keep the wire-type to record mapping in one
 * place; each record is independently a {@link MessageType}.
 *
 * <p>
 * {@link #load()} triggers static registration with
 * {@link MessageType#register(String, Class)};
 * {@link dev.arcp.envelope.ARCPMapper} invokes it at mapper creation.
 */
public final class SessionMessages {

	static {
		MessageType.register("session.open", SessionOpen.class);
		MessageType.register("session.challenge", SessionChallenge.class);
		MessageType.register("session.authenticate", SessionAuthenticate.class);
		MessageType.register("session.accepted", SessionAccepted.class);
		MessageType.register("session.unauthenticated", SessionUnauthenticated.class);
		MessageType.register("session.rejected", SessionRejected.class);
		MessageType.register("session.refresh", SessionRefresh.class);
		MessageType.register("session.evicted", SessionEvicted.class);
		MessageType.register("session.close", SessionClose.class);
	}

	private SessionMessages() {
	}

	/** Force class init so the static block runs. */
	public static void load() {
	}

	/** §8.1 — initial handshake message. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionOpen(@JsonProperty("arcp_version") String arcpVersion,
			@JsonProperty("credentials") @Nullable Credentials credentials,
			@JsonProperty("client") @Nullable Identity client,
			@JsonProperty("capabilities") Capabilities capabilities) implements MessageType {
		@Override
		public String type() {
			return "session.open";
		}
	}

	/** §8.1 — runtime-issued challenge. */
	public record SessionChallenge(@JsonProperty("nonce") String nonce,
			@JsonProperty("schemes") java.util.List<String> schemes) implements MessageType {
		@Override
		public String type() {
			return "session.challenge";
		}
	}

	/** §8.1 — caller's challenge response. */
	public record SessionAuthenticate(@JsonProperty("credentials") Credentials credentials,
			@JsonProperty("nonce") String nonce) implements MessageType {
		@Override
		public String type() {
			return "session.authenticate";
		}
	}

	/** §8.1, §8.3 — runtime acceptance with negotiated capabilities. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionAccepted(@JsonProperty("identity") Identity identity,
			@JsonProperty("capabilities") Capabilities capabilities,
			@JsonProperty("lease_expires_at") @Nullable Instant leaseExpiresAt) implements MessageType {
		@Override
		public String type() {
			return "session.accepted";
		}
	}

	/** §8.1 — credentials rejected. */
	public record SessionUnauthenticated(@JsonProperty("reason") String reason) implements MessageType {
		@Override
		public String type() {
			return "session.unauthenticated";
		}
	}

	/** §8.1, §7 — handshake rejection (e.g. required capability unsupported). */
	public record SessionRejected(@JsonProperty("code") ErrorCode code,
			@JsonProperty("message") String message) implements MessageType {
		@Override
		public String type() {
			return "session.rejected";
		}
	}

	/** §8.4 — mid-session re-authentication. */
	public record SessionRefresh(@JsonProperty("credentials") Credentials credentials) implements MessageType {
		@Override
		public String type() {
			return "session.refresh";
		}
	}

	/** §8.5 — unilateral runtime eviction. */
	public record SessionEvicted(@JsonProperty("reason") String reason) implements MessageType {
		@Override
		public String type() {
			return "session.evicted";
		}
	}

	/** §9 — graceful close from either party. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionClose(@JsonProperty("reason") @Nullable String reason) implements MessageType {
		@Override
		public String type() {
			return "session.close";
		}
	}
}
