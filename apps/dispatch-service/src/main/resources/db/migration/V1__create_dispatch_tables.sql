-- Flyway Migration V1: Dispatch Domain Tables, Constraints and Partial Unique Indexes
-- Authoritative Database: rhl_dispatch (PostgreSQL 15)

CREATE TABLE driver_offers (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    driver_id UUID NOT NULL,
    vehicle_id UUID,
    service_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    offered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    rejected_at TIMESTAMP WITH TIME ZONE,
    rejection_reason VARCHAR(500),
    revoked_at TIMESTAMP WITH TIME ZONE,
    revocation_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_driver_offers_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT chk_driver_offers_expiry CHECK (expires_at > offered_at)
);

-- Invariant: Exactly at most ONE offer can be in ACCEPTED status per trip (FR-MAT-011, NFR-REL-003)
CREATE UNIQUE INDEX uk_driver_offers_single_winning_accepted
    ON driver_offers (trip_id)
    WHERE status = 'ACCEPTED';

-- Invariant: Exactly at most ONE offer can be in PENDING status per driver (FR-DRV-012)
CREATE UNIQUE INDEX uk_driver_offers_single_pending_per_driver
    ON driver_offers (driver_id)
    WHERE status = 'PENDING';

-- Performance lookup indexes
CREATE INDEX idx_driver_offers_trip_status ON driver_offers (trip_id, status);
CREATE INDEX idx_driver_offers_driver_status ON driver_offers (driver_id, status);
CREATE INDEX idx_driver_offers_expires_at ON driver_offers (expires_at) WHERE status = 'PENDING';

-- Append-only audit trail for offer lifecycle transitions
CREATE TABLE driver_offer_history (
    id UUID PRIMARY KEY,
    offer_id UUID NOT NULL REFERENCES driver_offers(id) ON DELETE CASCADE,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_id UUID NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_driver_offer_history_offer_id ON driver_offer_history (offer_id);

-- Matching sessions tracking
CREATE TABLE matching_sessions (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    round_number INT NOT NULL DEFAULT 1,
    radius_meters INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_matching_sessions_status CHECK (status IN ('SEARCHING', 'MATCHED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX idx_matching_sessions_trip_id ON matching_sessions (trip_id);
