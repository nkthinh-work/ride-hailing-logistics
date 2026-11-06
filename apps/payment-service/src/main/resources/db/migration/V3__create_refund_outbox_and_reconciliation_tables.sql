-- Flyway Migration V3: Refund Aggregate, Refund Attempts, Status History, Outbox and Reconciliation Tables
-- Author: Senior Java Spring Boot Payment Engineer
-- Milestone: M08 Group 3

-- 1. Payment Refunds Aggregate Root
CREATE TABLE payment_refunds (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    trip_id UUID NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    requested_by VARCHAR(100) NOT NULL,
    approved_by VARCHAR(100),
    provider VARCHAR(50),
    provider_refund_order_id VARCHAR(100),
    provider_refund_trans_id VARCHAR(100),
    policy_note VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payment_transactions(id),
    CONSTRAINT chk_refund_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_refund_currency CHECK (currency IN ('VND', 'USD')),
    CONSTRAINT uk_refunds_payment_idempotency UNIQUE (payment_id, idempotency_key)
);

CREATE INDEX idx_refunds_payment_id ON payment_refunds (payment_id);
CREATE INDEX idx_refunds_customer_id ON payment_refunds (customer_id);
CREATE INDEX idx_refunds_status ON payment_refunds (status);
CREATE UNIQUE INDEX uk_refunds_provider_order ON payment_refunds (provider_refund_order_id) WHERE provider_refund_order_id IS NOT NULL;

-- 2. Refund Attempts Table
CREATE TABLE refund_attempts (
    id UUID PRIMARY KEY,
    refund_id UUID NOT NULL,
    attempt_number INT NOT NULL,
    provider VARCHAR(50) NOT NULL,
    provider_refund_order_id VARCHAR(100) NOT NULL,
    provider_refund_trans_id VARCHAR(100),
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(30) NOT NULL,
    error_code VARCHAR(50),
    error_message VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_refund_attempts_refund FOREIGN KEY (refund_id) REFERENCES payment_refunds(id) ON DELETE CASCADE,
    CONSTRAINT uk_refund_attempts_provider_order UNIQUE (provider_refund_order_id)
);

CREATE UNIQUE INDEX uk_refund_attempts_single_active ON refund_attempts (refund_id) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_refund_attempts_refund_id ON refund_attempts (refund_id);

-- 3. Refund Status History (Append-only Financial Audit Log)
CREATE TABLE refund_status_history (
    id UUID PRIMARY KEY,
    refund_id UUID NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    actor_role VARCHAR(50) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_refund_history_refund FOREIGN KEY (refund_id) REFERENCES payment_refunds(id) ON DELETE CASCADE
);

CREATE INDEX idx_refund_history_refund_id ON refund_status_history (refund_id);

-- 4. Transactional Outbox for Financial Events
CREATE TABLE payment_outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    correlation_id VARCHAR(100),
    recipient_id UUID,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_outbox_pending ON payment_outbox_events (status, next_retry_at) WHERE status = 'PENDING';
CREATE INDEX idx_payment_outbox_aggregate ON payment_outbox_events (aggregate_type, aggregate_id);

-- 5. Financial Reconciliation Records
CREATE TABLE payment_reconciliation_records (
    id UUID PRIMARY KEY,
    payment_id UUID,
    provider VARCHAR(50) NOT NULL,
    provider_order_id VARCHAR(100),
    mismatch_type VARCHAR(50) NOT NULL,
    local_status VARCHAR(30) NOT NULL,
    provider_status VARCHAR(30),
    local_amount NUMERIC(12, 2),
    provider_amount NUMERIC(12, 2),
    resolution_state VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    details TEXT,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by VARCHAR(100),

    CONSTRAINT fk_reconciliation_payment FOREIGN KEY (payment_id) REFERENCES payment_transactions(id) ON DELETE SET NULL
);

CREATE INDEX idx_reconciliation_payment_id ON payment_reconciliation_records (payment_id);
CREATE INDEX idx_reconciliation_resolution_state ON payment_reconciliation_records (resolution_state);
