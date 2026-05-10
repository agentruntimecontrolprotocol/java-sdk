package dev.arcp.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.envelope.ARCPMapper;
import dev.arcp.envelope.Envelope;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import dev.arcp.ids.MessageId;
import dev.arcp.ids.SessionId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Append-only SQLite event log (RFC §6.4 transport idempotency, §19 message-id
 * resume).
 *
 * <p>
 * Inserts are keyed on {@code (session_id, id)} with {@code ON CONFLICT DO
 * NOTHING}, satisfying transport-level idempotency. Replay returns envelopes
 * ordered by {@code id}; ULID-encoded ids sort chronologically, matching §13.3
 * backfill ordering and §19 resume semantics.
 *
 * <p>
 * JDBC is synchronous; virtual threads handle blocking IO without additional
 * ceremony. Writes are serialized via {@code synchronized} blocks to avoid
 * SQLite's per-database write contention.
 */
public final class EventLog implements AutoCloseable {

	private static final String SCHEMA_RESOURCE = "/arcp/store/schema.sql";

	private final Connection conn;
	private final ObjectMapper mapper;
	private final Object writeLock = new Object();

	private EventLog(Connection conn, ObjectMapper mapper) {
		this.conn = conn;
		this.mapper = mapper;
	}

	/**
	 * Open (and initialize) an event log at the given JDBC URL.
	 *
	 * @param jdbcUrl
	 *            e.g. {@code jdbc:sqlite::memory:} or
	 *            {@code jdbc:sqlite:/tmp/arcp.db}.
	 */
	public static EventLog open(String jdbcUrl) {
		return open(jdbcUrl, ARCPMapper.create());
	}

	/** Open with a caller-supplied mapper. */
	public static EventLog open(String jdbcUrl, ObjectMapper mapper) {
		try {
			Connection conn = DriverManager.getConnection(jdbcUrl);
			applySchema(conn);
			return new EventLog(conn, mapper);
		} catch (SQLException e) {
			throw new ARCPException(ErrorCode.INTERNAL, "could not open event log: " + jdbcUrl, e);
		}
	}

	private static void applySchema(Connection conn) {
		String ddl = readResource();
		try (Statement st = conn.createStatement()) {
			for (String stmt : ddl.split(";", -1)) {
				String trimmed = stmt.strip();
				if (!trimmed.isEmpty()) {
					st.execute(trimmed);
				}
			}
		} catch (SQLException e) {
			throw new ARCPException(ErrorCode.INTERNAL, "schema apply failed", e);
		}
	}

	private static String readResource() {
		try (InputStream in = EventLog.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new ARCPException(ErrorCode.INTERNAL, "missing resource " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Idempotently append an envelope.
	 *
	 * @return {@code true} if newly inserted, {@code false} if the
	 *         {@code (session_id, id)} pair already existed (deduped).
	 * @throws ARCPException
	 *             with {@link ErrorCode#FAILED_PRECONDITION} if the envelope has no
	 *             {@code session_id} (events outside a session are not persisted by
	 *             the log).
	 */
	public boolean append(Envelope env) {
		if (env.sessionId() == null) {
			throw new ARCPException(ErrorCode.FAILED_PRECONDITION,
					"envelope " + env.id().asString() + " has no session_id");
		}
		String body;
		try {
			body = mapper.writeValueAsString(env);
		} catch (JsonProcessingException e) {
			throw new ARCPException(ErrorCode.INTERNAL, "envelope serialization failed", e);
		}
		synchronized (writeLock) {
			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO arcp_events"
					+ "(session_id, id, type, timestamp, correlation_id, causation_id, trace_id, body) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " + "ON CONFLICT(session_id, id) DO NOTHING")) {
				ps.setString(1, env.sessionId().asString());
				ps.setString(2, env.id().asString());
				ps.setString(3, env.type());
				ps.setString(4, env.timestamp().toString());
				ps.setString(5, env.correlationId() == null ? null : env.correlationId().asString());
				ps.setString(6, env.causationId() == null ? null : env.causationId().asString());
				ps.setString(7, env.traceId() == null ? null : env.traceId().asString());
				ps.setString(8, body);
				int rows = ps.executeUpdate();
				return rows == 1;
			} catch (SQLException e) {
				throw new ARCPException(ErrorCode.INTERNAL, "append failed", e);
			}
		}
	}

	/**
	 * Replay all events for a session in ULID order, starting strictly after
	 * {@code afterId} when present (RFC §19).
	 */
	public List<Envelope> replay(SessionId sessionId, @Nullable MessageId afterId) {
		String sql = afterId == null
				? "SELECT body FROM arcp_events WHERE session_id = ? ORDER BY id ASC"
				: "SELECT body FROM arcp_events WHERE session_id = ? AND id > ? ORDER BY id ASC";
		List<Envelope> out = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, sessionId.asString());
			if (afterId != null) {
				ps.setString(2, afterId.asString());
			}
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(mapper.readValue(rs.getString(1), Envelope.class));
				}
			}
		} catch (SQLException e) {
			throw new ARCPException(ErrorCode.INTERNAL, "replay failed", e);
		} catch (JsonProcessingException e) {
			throw new ARCPException(ErrorCode.DATA_LOSS, "stored envelope undecodable", e);
		}
		return out;
	}

	/**
	 * Record a {@code (principal, idempotency_key) -> response_id} mapping (RFC
	 * §6.4 logical idempotency). Returns {@link Optional#empty()} if the mapping
	 * was newly inserted, or the prior response id when the key was already
	 * present.
	 */
	public Optional<MessageId> recordIdempotency(String principal, String idempotencyKey, MessageId responseId,
			java.time.Instant insertedAt) {
		synchronized (writeLock) {
			try (PreparedStatement check = conn.prepareStatement(
					"SELECT response_id FROM arcp_idempotency WHERE principal = ? AND idempotency_key = ?")) {
				check.setString(1, principal);
				check.setString(2, idempotencyKey);
				try (ResultSet rs = check.executeQuery()) {
					if (rs.next()) {
						return Optional.of(MessageId.of(rs.getString(1)));
					}
				}
			} catch (SQLException e) {
				throw new ARCPException(ErrorCode.INTERNAL, "idempotency lookup failed", e);
			}
			try (PreparedStatement ins = conn.prepareStatement(
					"INSERT INTO arcp_idempotency(principal, idempotency_key, response_id, inserted_at) VALUES (?, ?, ?, ?)")) {
				ins.setString(1, principal);
				ins.setString(2, idempotencyKey);
				ins.setString(3, responseId.asString());
				ins.setString(4, insertedAt.toString());
				ins.executeUpdate();
				return Optional.empty();
			} catch (SQLException e) {
				throw new ARCPException(ErrorCode.INTERNAL, "idempotency insert failed", e);
			}
		}
	}

	@Override
	public void close() {
		try {
			conn.close();
		} catch (SQLException e) {
			throw new ARCPException(ErrorCode.INTERNAL, "close failed", e);
		}
	}
}
