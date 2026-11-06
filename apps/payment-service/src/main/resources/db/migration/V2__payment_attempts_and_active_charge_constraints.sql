-- V2__payment_attempts_and_active_charge_constraints.sql
-- M08 Group 2: Payment Attempt Lifecycle, Active Concurrency Invariant & Provider Identity

-- 1. Partial unique index on payment_transactions to ensure at most ONE active obligation in PENDING/PROCESSING per trip
CREATE UNIQUE INDEX uk_payments_trip_single_active 
    ON payment_transactions (trip_id) WHERE status IN ('PENDING', 'PROCESSING');

-- 2. Payment Attempts table tracking each provider charge attempt for an obligation
CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payment_transactions (id) ON DELETE RESTRICT,
    attempt_number INT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_order_id VARCHAR(128) NOT NULL,
    provider_transaction_id VARCHAR(128),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'VND',
    status VARCHAR(32) NOT NULL,
    checkout_url VARCHAR(1000),
    idempotency_reference VARCHAR(128) NOT NULL,
    failure_code VARCHAR(64),
    failure_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,

    CONSTRAINT ck_payment_attempt_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
    )
);

-- Unique provider order reference across provider attempts
CREATE UNIQUE INDEX uk_payment_attempts_provider_order 
    ON payment_attempts (provider, provider_order_id);

-- At most one active provider charge attempt per payment transaction
CREATE UNIQUE INDEX uk_payment_attempts_single_active 
    ON payment_attempts (payment_id) WHERE status IN ('PENDING', 'PROCESSING');

-- Operational indexes for attempt lookup
CREATE INDEX idx_payment_attempts_payment_id ON payment_attempts (payment_id);
CREATE INDEX idx_payment_attempts_created_at ON payment_attempts (created_at DESC);
CREATE INDEX idx_payment_attempts_status ON payment_attempts (status);
