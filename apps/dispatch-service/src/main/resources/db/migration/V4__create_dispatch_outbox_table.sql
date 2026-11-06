-- Flyway Migration V4: Transactional Outbox Pattern for Dispatch Domain Events
-- Authoritative Database: rhl_dispatch (PostgreSQL 15)

CREATE TABLE IF NOT EXISTS dispatch_outbox_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    recipient_id UUID NOT NULL,
    correlation_id VARCHAR(64),
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_dispatch_outbox_pending
    ON dispatch_outbox_events (status, next_retry_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_dispatch_outbox_aggregate
    ON dispatch_outbox_events (aggregate_id, event_type);
