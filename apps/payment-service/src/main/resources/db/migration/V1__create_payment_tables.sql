-- V1__create_payment_tables.sql
-- Payment Transaction Domain, Financial Audit & Idempotency Foundation (M08 Group 1)

CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'VND',
    payment_method VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    provider_reference VARCHAR(128),
    failure_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,

    CONSTRAINT ck_payment_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_payment_method CHECK (
        payment_method IN ('CASH', 'DIGITAL_WALLET', 'CREDIT_CARD')
    )
);

-- Idempotency scope: Exactly one transaction record per customer + idempotency_key
CREATE UNIQUE INDEX uk_payments_customer_idempotency 
    ON payment_transactions (customer_id, idempotency_key);

-- Financial Invariant: Exactly one SUCCEEDED payment obligation per trip (Zero Double Charging)
CREATE UNIQUE INDEX uk_payments_trip_single_success 
    ON payment_transactions (trip_id) WHERE status = 'SUCCEEDED';

-- Operational and lookup indexes
CREATE INDEX idx_payments_trip_id ON payment_transactions (trip_id);
CREATE INDEX idx_payments_customer_id ON payment_transactions (customer_id);
CREATE INDEX idx_payments_status ON payment_transactions (status);
CREATE INDEX idx_payments_created_at ON payment_transactions (created_at DESC);

-- Financial Audit Log Table (Append-Only Audit Trail)
CREATE TABLE payment_status_history (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payment_transactions (id) ON DELETE RESTRICT,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    actor_role VARCHAR(64) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_history_payment_id ON payment_status_history (payment_id);
CREATE INDEX idx_payment_history_created_at ON payment_status_history (created_at DESC);
