package dev.arcp.capability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Negotiated capability set (RFC §7). Absent boolean capabilities are treated
 * as {@code false}; the explicit-{@code false} encoding here matches that
 * default. Extension namespaces are listed in {@link #extensions}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Capabilities(@JsonProperty("anonymous") boolean anonymous, @JsonProperty("streaming") boolean streaming,
		@JsonProperty("human_input") boolean humanInput, @JsonProperty("permissions") boolean permissions,
		@JsonProperty("artifacts") boolean artifacts, @JsonProperty("subscriptions") boolean subscriptions,
		@JsonProperty("interrupt") boolean interrupt,
		@JsonProperty("heartbeat_recovery") @Nullable String heartbeatRecovery,
		@JsonProperty("heartbeat_interval_seconds") int heartbeatIntervalSeconds,
		@JsonProperty("binary_encoding") @Nullable List<String> binaryEncoding,
		@JsonProperty("extensions") @Nullable Set<String> extensions) {

	/** Default empty capability set; every flag is false. */
	public static final Capabilities NONE = new Capabilities(false, false, false, false, false, false, false, null, 0,
			null, null);

	/** Capability set advertised by the v0.1 reference runtime. */
	public static Capabilities reference() {
		return new Capabilities(true, true, true, true, true, true, true, "block", 30, List.of("base64"), Set.of());
	}
}
