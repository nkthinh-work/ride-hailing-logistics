-- =============================================================================
-- Migration: V2__quote_uniqueness_and_active_trip_index.sql
-- Bounded Context: trip-service (M05 Group 2)
-- Enforces:
-- 1. Exactly 1 Trip per Quote: Prevents Quote Replay attacks (Section 7)
-- 2. One Active Trip per Customer: Partial unique index for non-terminal statuses (Section 8, FR-TRIP-007)
-- =============================================================================

-- Enforce 1 Quote -> Maximum 1 Trip across the entire database
CREATE UNIQUE INDEX IF NOT EXISTS uk_trips_quote_id
    ON trips (quote_id);

-- Enforce Customer has at most 1 active (non-terminal) trip at any given time (FR-TRIP-007)
CREATE UNIQUE INDEX IF NOT EXISTS uk_trips_customer_single_active
    ON trips (customer_id)
    WHERE status IN ('CREATED', 'MATCHING', 'ACCEPTED', 'PICKING_UP', 'ARRIVED', 'IN_TRIP');
