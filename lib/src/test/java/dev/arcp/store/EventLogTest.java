package dev.arcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.envelope.Envelope;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import dev.arcp.ids.MessageId;
import dev.arcp.ids.SessionId;
import dev.arcp.messages.control.Ping;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventLogTest {

	private EventLog log;

	@BeforeEach
	void setUp() {
		log = EventLog.open("jdbc:sqlite::memory:");
	}

	@AfterEach
	void tearDown() {
		log.close();
	}

	private static Envelope ping(SessionId sid, MessageId id, Instant t) {
		return new Envelope("1.0", id, "ping", t, sid, null, null, null, null, null, null, null, null, null, null, null,
				null, null, new Ping("x"));
	}

	@Test
	void appendIsIdempotentOnSessionAndId() {
		SessionId sid = SessionId.random();
		MessageId id = MessageId.random();
		Envelope env = ping(sid, id, Instant.parse("2026-05-10T00:00:00Z"));
		assertThat(log.append(env)).isTrue();
		assertThat(log.append(env)).isFalse();
		assertThat(log.replay(sid, null)).hasSize(1);
	}

	@Test
	void replayReturnsUlidOrder() {
		SessionId sid = SessionId.random();
		Envelope a = ping(sid, MessageId.of("01HF000000000000000000A001"), Instant.parse("2026-05-10T00:00:01Z"));
		Envelope b = ping(sid, MessageId.of("01HF000000000000000000B002"), Instant.parse("2026-05-10T00:00:02Z"));
		Envelope c = ping(sid, MessageId.of("01HF000000000000000000C003"), Instant.parse("2026-05-10T00:00:03Z"));
		log.append(c);
		log.append(a);
		log.append(b);

		List<Envelope> events = log.replay(sid, null);
		assertThat(events).extracting(e -> e.id().asString()).containsExactly(a.id().asString(), b.id().asString(),
				c.id().asString());
	}

	@Test
	void replayAfterMessageIdSkipsPrior() {
		SessionId sid = SessionId.random();
		Envelope a = ping(sid, MessageId.of("01HF000000000000000000A001"), Instant.parse("2026-05-10T00:00:01Z"));
		Envelope b = ping(sid, MessageId.of("01HF000000000000000000B002"), Instant.parse("2026-05-10T00:00:02Z"));
		Envelope c = ping(sid, MessageId.of("01HF000000000000000000C003"), Instant.parse("2026-05-10T00:00:03Z"));
		log.append(a);
		log.append(b);
		log.append(c);

		List<Envelope> after = log.replay(sid, a.id());
		assertThat(after).extracting(e -> e.id().asString()).containsExactly(b.id().asString(), c.id().asString());
	}

	@Test
	void replayIsScopedPerSession() {
		SessionId s1 = SessionId.random();
		SessionId s2 = SessionId.random();
		log.append(ping(s1, MessageId.random(), Instant.parse("2026-05-10T00:00:00Z")));
		log.append(ping(s2, MessageId.random(), Instant.parse("2026-05-10T00:00:00Z")));

		assertThat(log.replay(s1, null)).hasSize(1);
		assertThat(log.replay(s2, null)).hasSize(1);
	}

	@Test
	void appendWithoutSessionIdRejected() {
		Envelope orphan = Envelope.of(MessageId.random(), Instant.now(), new Ping("x"));
		assertThatThrownBy(() -> log.append(orphan)).isInstanceOf(ARCPException.class)
				.satisfies(e -> assertThat(((ARCPException) e).code()).isEqualTo(ErrorCode.FAILED_PRECONDITION));
	}

	@Test
	void idempotencyMappingReturnsPriorOutcome() {
		MessageId first = MessageId.random();
		Instant t = Instant.parse("2026-05-10T00:00:00Z");

		Optional<MessageId> initial = log.recordIdempotency("alice", "op-1", first, t);
		assertThat(initial).isEmpty();

		MessageId second = MessageId.random();
		Optional<MessageId> replay = log.recordIdempotency("alice", "op-1", second, t);
		assertThat(replay).hasValue(first);
	}

	@Test
	void idempotencyKeyedByPrincipal() {
		MessageId a = MessageId.random();
		MessageId b = MessageId.random();
		Instant t = Instant.parse("2026-05-10T00:00:00Z");

		log.recordIdempotency("alice", "op-1", a, t);
		Optional<MessageId> bobsTurn = log.recordIdempotency("bob", "op-1", b, t);
		assertThat(bobsTurn).isEmpty();
	}

	@Test
	void openWithBogusUrlThrowsArcpException() {
		assertThatThrownBy(() -> EventLog.open("jdbc:invalid:nope")).isInstanceOf(ARCPException.class);
	}
}
