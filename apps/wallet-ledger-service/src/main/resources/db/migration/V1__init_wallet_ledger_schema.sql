-- =============================================================================
-- Migration V1: Driver Wallet Domain, Immutable Double-Entry Ledger & Inbox
-- =============================================================================

CREATE TABLE driver_wallets (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    available_balance NUMERIC(18, 2) NOT NULL DEFAULT 0.00,
    pending_balance NUMERIC(18, 2) NOT NULL DEFAULT 0.00,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_driver_wallets_driver_currency UNIQUE (driver_id, currency),
    CONSTRAINT ck_driver_wallets_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LOCKED')),
    CONSTRAINT ck_driver_wallets_balance_non_negative CHECK (available_balance >= 0.00),
    CONSTRAINT ck_driver_wallets_pending_non_negative CHECK (pending_balance >= 0.00)
);

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    account_code VARCHAR(100) NOT NULL UNIQUE,
    account_type VARCHAR(30) NOT NULL,
    normal_balance VARCHAR(10) NOT NULL,
    owner_type VARCHAR(30) NOT NULL,
    owner_id UUID,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ledger_accounts_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT ck_ledger_accounts_normal CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_accounts_owner_type CHECK (owner_type IN ('DRIVER', 'PLATFORM', 'CUSTOMER', 'SYSTEM')),
    CONSTRAINT ck_ledger_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE INDEX idx_ledger_accounts_owner ON ledger_accounts (owner_type, owner_id);

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    transaction_type VARCHAR(50) NOT NULL,
    business_reference VARCHAR(100) NOT NULL UNIQUE,
    external_event_id UUID UNIQUE,
    source_service VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(30) NOT NULL DEFAULT 'POSTED',
    correlation_id VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL,
    reversed_transaction_id UUID REFERENCES ledger_transactions(id),
    CONSTRAINT ck_ledger_transactions_status CHECK (status IN ('POSTED', 'REVERSED'))
);

CREATE INDEX idx_ledger_transactions_posted_at ON ledger_transactions (posted_at);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES ledger_accounts(id) ON DELETE RESTRICT,
    wallet_id UUID REFERENCES driver_wallets(id) ON DELETE RESTRICT,
    direction VARCHAR(10) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount > 0.00)
);

CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_wallet_id ON ledger_entries (wallet_id);

CREATE TABLE financial_inbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    source_service VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    correlation_id VARCHAR(100),
    payload TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_POLICY',
    failure_reason TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    CONSTRAINT ck_inbox_status CHECK (status IN ('PROCESSED', 'DUPLICATE', 'FAILED', 'PENDING_POLICY'))
);

CREATE INDEX idx_inbox_entity_id ON financial_inbox_events (entity_id);

CREATE TABLE wallet_audit_logs (
    id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL REFERENCES driver_wallets(id) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30),
    actor_id VARCHAR(100) NOT NULL,
    actor_role VARCHAR(50) NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_wallet_audit_wallet_id ON wallet_audit_logs (wallet_id);
