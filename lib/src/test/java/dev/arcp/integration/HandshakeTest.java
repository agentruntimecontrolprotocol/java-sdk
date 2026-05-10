package dev.arcp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.arcp.auth.Credentials;
import dev.arcp.auth.Principal;
import dev.arcp.auth.StaticBearerValidator;
import dev.arcp.capability.Capabilities;
import dev.arcp.client.ARCPClient;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import dev.arcp.messages.session.SessionMessages;
import dev.arcp.runtime.ARCPRuntime;
import dev.arcp.runtime.SessionState;
import dev.arcp.transport.MemoryTransport;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HandshakeTest {

	private static final Clock CLOCK = Clock.fixed(java.time.Instant.parse("2026-05-10T00:00:00Z"),
			java.time.ZoneOffset.UTC);

	@Test
	void bearerHappyPath() throws Exception {
		MemoryTransport[] pair = MemoryTransport.pair();
		StaticBearerValidator validator = new StaticBearerValidator(Map.of("tok-1", new Principal("alice", "trusted")));
		try (ARCPRuntime runtime = new ARCPRuntime(pair[0], validator, Capabilities.reference(), CLOCK)) {
			runtime.start();
			ARCPClient client = new ARCPClient(pair[1], CLOCK);

			SessionMessages.SessionAccepted accepted = client.openSession(new Credentials.BearerCredentials("tok-1"),
					null, Capabilities.reference(), Duration.ofSeconds(2));

			assertThat(accepted.identity().kind()).isEqualTo("arcp-java");
			assertThat(accepted.capabilities().streaming()).isTrue();
			await().atMost(2, TimeUnit.SECONDS).until(() -> runtime.state() == SessionState.ACCEPTED);
			assertThat(runtime.principal()).isEqualTo(new Principal("alice", "trusted"));
		}
	}

	@Test
	void badBearerTokenIsUnauthenticated() throws Exception {
		MemoryTransport[] pair = MemoryTransport.pair();
		StaticBearerValidator validator = new StaticBearerValidator(Map.of());
		try (ARCPRuntime runtime = new ARCPRuntime(pair[0], validator, Capabilities.reference(), CLOCK)) {
			runtime.start();
			ARCPClient client = new ARCPClient(pair[1], CLOCK);
			assertThatThrownBy(() -> client.openSession(new Credentials.BearerCredentials("nope"), null,
					Capabilities.reference(), Duration.ofSeconds(2))).isInstanceOf(ARCPException.class)
					.satisfies(e -> assertThat(((ARCPException) e).code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
		}
	}

	@Test
	void anonymousRefusedWhenCapabilityNotAdvertised() throws Exception {
		MemoryTransport[] pair = MemoryTransport.pair();
		StaticBearerValidator validator = new StaticBearerValidator(Map.of());
		Capabilities noAnonymous = new Capabilities(false, true, false, false, false, false, false, "block", 30,
				java.util.List.of("base64"), java.util.Set.of());
		try (ARCPRuntime runtime = new ARCPRuntime(pair[0], validator, noAnonymous, CLOCK)) {
			runtime.start();
			ARCPClient client = new ARCPClient(pair[1], CLOCK);
			assertThatThrownBy(() -> client.openSession(new Credentials.NoneCredentials(), null,
					Capabilities.reference(), Duration.ofSeconds(2))).isInstanceOf(ARCPException.class);
		}
	}

	@Test
	void capabilityNegotiationIntersects() throws Exception {
		MemoryTransport[] pair = MemoryTransport.pair();
		StaticBearerValidator validator = new StaticBearerValidator(Map.of("tok", new Principal("u", "trusted")));
		Capabilities clientCaps = new Capabilities(false, true, false, false, false, false, false, "block", 30,
				java.util.List.of("base64"), java.util.Set.of());
		try (ARCPRuntime runtime = new ARCPRuntime(pair[0], validator, Capabilities.reference(), CLOCK)) {
			runtime.start();
			ARCPClient client = new ARCPClient(pair[1], CLOCK);
			SessionMessages.SessionAccepted accepted = client.openSession(new Credentials.BearerCredentials("tok"),
					null, clientCaps, Duration.ofSeconds(2));
			assertThat(accepted.capabilities().humanInput()).isFalse(); // client said false
			assertThat(accepted.capabilities().streaming()).isTrue(); // both true
		}
	}
}
