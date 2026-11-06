-- V2__create_fare_quotes.sql
-- Database schema for Fare Quotes and Immutable Snapshots (FR-PRI-013..018)

CREATE TABLE fare_quotes (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    service_type VARCHAR(30) NOT NULL,
    vehicle_type VARCHAR(30) NOT NULL,
    region_id VARCHAR(50) NOT NULL DEFAULT 'DEFAULT',
    pickup_latitude NUMERIC(10, 7) NOT NULL,
    pickup_longitude NUMERIC(10, 7) NOT NULL,
    pickup_address VARCHAR(255),
    dropoff_latitude NUMERIC(10, 7) NOT NULL,
    dropoff_longitude NUMERIC(10, 7) NOT NULL,
    dropoff_address VARCHAR(255),
    distance_meters BIGINT NOT NULL,
    duration_seconds BIGINT NOT NULL,
    route_provider VARCHAR(50) NOT NULL,
    is_simulated BOOLEAN NOT NULL DEFAULT FALSE,
    pricing_rule_id VARCHAR(50) NOT NULL,
    pricing_rule_version VARCHAR(30) NOT NULL,

    -- Immutable Fare Breakdown Snapshot (FR-PRI-002, 013, 016)
    base_fare NUMERIC(14, 2) NOT NULL,
    included_distance_km NUMERIC(8, 2) NOT NULL,
    chargeable_distance_km NUMERIC(8, 2) NOT NULL,
    distance_fare NUMERIC(14, 2) NOT NULL,
    duration_minutes NUMERIC(8, 2) NOT NULL,
    time_fare NUMERIC(14, 2) NOT NULL,
    standard_fare NUMERIC(14, 2) NOT NULL,
    minimum_fare NUMERIC(14, 2) NOT NULL,
    pre_surge_fare NUMERIC(14, 2) NOT NULL,
    surge_multiplier NUMERIC(6, 4) NOT NULL DEFAULT 1.0000,
    surged_fare NUMERIC(14, 2) NOT NULL,
    additional_fees NUMERIC(14, 2) NOT NULL DEFAULT 0.00,
    tax NUMERIC(14, 2) NOT NULL DEFAULT 0.00,
    discount NUMERIC(14, 2) NOT NULL DEFAULT 0.00,
    total_fare NUMERIC(14, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'VND',

    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_fare_quotes_service_type
        CHECK (service_type IN ('RIDE', 'DELIVERY')),

    CONSTRAINT ck_fare_quotes_vehicle_type
        CHECK (vehicle_type IN ('MOTORBIKE', 'CAR_4_SEAT', 'CAR_7_SEAT')),

    CONSTRAINT ck_fare_quotes_coordinates
        CHECK (pickup_latitude BETWEEN -90 AND 90
            AND pickup_longitude BETWEEN -180 AND 180
            AND dropoff_latitude BETWEEN -90 AND 90
            AND dropoff_longitude BETWEEN -180 AND 180),

    CONSTRAINT ck_fare_quotes_fares_non_negative
        CHECK (base_fare >= 0
            AND distance_fare >= 0
            AND time_fare >= 0
            AND minimum_fare >= 0
            AND total_fare >= 0
            AND surge_multiplier >= 1.0),

    CONSTRAINT ck_fare_quotes_expires_after_created
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_fare_quotes_customer_created
    ON fare_quotes(customer_id, created_at DESC);

CREATE INDEX idx_fare_quotes_expires_at
    ON fare_quotes(expires_at);
