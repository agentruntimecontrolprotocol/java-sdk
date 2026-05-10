-- ARCP event log schema (RFC §6.4 idempotency, §19 resume).
-- Applied at startup with CREATE ... IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS arcp_events (
    session_id      TEXT NOT NULL,
    id              TEXT NOT NULL,
    type            TEXT NOT NULL,
    timestamp       TEXT NOT NULL,
    correlation_id  TEXT,
    causation_id    TEXT,
    trace_id        TEXT,
    body            TEXT NOT NULL,
    PRIMARY KEY (session_id, id)
);

CREATE INDEX IF NOT EXISTS arcp_events_by_correlation ON arcp_events(correlation_id);
CREATE INDEX IF NOT EXISTS arcp_events_by_causation   ON arcp_events(causation_id);
CREATE INDEX IF NOT EXISTS arcp_events_by_trace       ON arcp_events(trace_id);
CREATE INDEX IF NOT EXISTS arcp_events_by_session_ts  ON arcp_events(session_id, timestamp);

-- §6.4 logical idempotency: (principal, idempotency_key) -> prior response id.
CREATE TABLE IF NOT EXISTS arcp_idempotency (
    principal       TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    response_id     TEXT NOT NULL,
    inserted_at     TEXT NOT NULL,
    PRIMARY KEY (principal, idempotency_key)
);
