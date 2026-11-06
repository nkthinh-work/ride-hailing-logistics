-- Flyway Migration V4: Transactional Outbox Pattern for Trip Domain Events
-- Authoritative Database: rhl_trip (PostgreSQL 15)

CREATE TABLE IF NOT EXISTS trip_outbox_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL DEFAULT 'TRIP',
    aggregate_id UUID NOT NULL,
    recipient_id UUID NOT NULL,
    recipient_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    correlation_id VARCHAR(64),
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_trip_outbox_pending
    ON trip_outbox_events (status, next_retry_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_trip_outbox_aggregate
    ON trip_outbox_events (aggregate_id, event_type);
