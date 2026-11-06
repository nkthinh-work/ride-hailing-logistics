-- =============================================================================
-- Migration: V1__create_trips_tables.sql
-- Bounded Context: trip-service (M05)
-- Requirements: FR-TRIP-001..015, FR-CAN-001..007, DR-001..009, BR-002, BR-008
-- State Machine: CREATED, MATCHING, ACCEPTED, PICKING_UP, ARRIVED, IN_TRIP, COMPLETED, CANCELLED, NO_DRIVER
-- =============================================================================

CREATE TABLE IF NOT EXISTS trips (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    customer_id UUID NOT NULL,
    driver_id UUID,
    quote_id UUID NOT NULL,
    service_type VARCHAR(32) NOT NULL,
    vehicle_type VARCHAR(32) NOT NULL,
    vehicle_id UUID,

    -- Pickup location
    pickup_latitude NUMERIC(10, 7) NOT NULL,
    pickup_longitude NUMERIC(10, 7) NOT NULL,
    pickup_address VARCHAR(500) NOT NULL,

    -- Dropoff location
    dropoff_latitude NUMERIC(10, 7) NOT NULL,
    dropoff_longitude NUMERIC(10, 7) NOT NULL,
    dropoff_address VARCHAR(500) NOT NULL,

    -- Fare Quote Snapshot (DR-004)
    estimated_distance_km NUMERIC(8, 2) NOT NULL,
    estimated_duration_minutes INT NOT NULL,
    estimated_fare NUMERIC(12, 2) NOT NULL,
    surge_multiplier NUMERIC(4, 2) NOT NULL DEFAULT 1.00,
    currency VARCHAR(8) NOT NULL DEFAULT 'VND',
    pricing_rule_version VARCHAR(64) NOT NULL,

    -- Trip Status & Lifecycle
    status VARCHAR(32) NOT NULL,

    -- Lifecycle Timestamps (UTC)
    requested_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    arrived_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,

    -- Cancellation Metadata (FR-CAN-001..007)
    cancellation_reason VARCHAR(500),
    cancelled_by UUID,
    cancellation_actor_role VARCHAR(32),
    cancellation_fee NUMERIC(12, 2),

    -- Concurrency Optimistic Lock
    version BIGINT NOT NULL DEFAULT 0,

    -- Audit Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    CONSTRAINT chk_trips_status CHECK (
        status IN (
            'CREATED',
            'MATCHING',
            'ACCEPTED',
            'PICKING_UP',
            'ARRIVED',
            'IN_TRIP',
            'COMPLETED',
            'CANCELLED',
            'NO_DRIVER'
        )
    ),
    CONSTRAINT chk_trips_service_type CHECK (service_type IN ('RIDE', 'DELIVERY')),
    CONSTRAINT chk_trips_estimated_distance CHECK (estimated_distance_km >= 0),
    CONSTRAINT chk_trips_estimated_duration CHECK (estimated_duration_minutes >= 0),
    CONSTRAINT chk_trips_estimated_fare CHECK (estimated_fare >= 0),
    CONSTRAINT chk_trips_surge_multiplier CHECK (surge_multiplier >= 1.00),
    CONSTRAINT chk_trips_pickup_lat CHECK (pickup_latitude BETWEEN -90.0 AND 90.0),
    CONSTRAINT chk_trips_pickup_lng CHECK (pickup_longitude BETWEEN -180.0 AND 180.0),
    CONSTRAINT chk_trips_dropoff_lat CHECK (dropoff_latitude BETWEEN -90.0 AND 90.0),
    CONSTRAINT chk_trips_dropoff_lng CHECK (dropoff_longitude BETWEEN -180.0 AND 180.0)
);

-- Idempotency unique constraint per customer (FR-TRIP-002, COM-008)
CREATE UNIQUE INDEX IF NOT EXISTS uk_trips_customer_idempotency
    ON trips (customer_id, idempotency_key);

-- Indexes for queries & state checks
CREATE INDEX IF NOT EXISTS idx_trips_customer_id ON trips (customer_id);
CREATE INDEX IF NOT EXISTS idx_trips_driver_id ON trips (driver_id);
CREATE INDEX IF NOT EXISTS idx_trips_status ON trips (status);
CREATE INDEX IF NOT EXISTS idx_trips_customer_status ON trips (customer_id, status);
CREATE INDEX IF NOT EXISTS idx_trips_driver_status ON trips (driver_id, status);
CREATE INDEX IF NOT EXISTS idx_trips_created_at ON trips (created_at);

-- =============================================================================
-- Table: trip_status_history (Audit Trail for Status Transitions)
-- Requirements: DR-005, FR-TRIP-010, BR-014
-- =============================================================================
CREATE TABLE IF NOT EXISTS trip_status_history (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_id UUID NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_trip_status_history_trip
        FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE RESTRICT,

    CONSTRAINT chk_history_to_status CHECK (
        to_status IN (
            'CREATED',
            'MATCHING',
            'ACCEPTED',
            'PICKING_UP',
            'ARRIVED',
            'IN_TRIP',
            'COMPLETED',
            'CANCELLED',
            'NO_DRIVER'
        )
    ),
    CONSTRAINT chk_history_from_status CHECK (
        from_status IS NULL OR from_status IN (
            'CREATED',
            'MATCHING',
            'ACCEPTED',
            'PICKING_UP',
            'ARRIVED',
            'IN_TRIP',
            'COMPLETED',
            'CANCELLED',
            'NO_DRIVER'
        )
    )
);

CREATE INDEX IF NOT EXISTS idx_trip_status_history_trip_id
    ON trip_status_history (trip_id, created_at);
