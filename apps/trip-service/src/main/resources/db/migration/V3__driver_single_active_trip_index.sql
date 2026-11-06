-- =============================================================================
-- Migration: V3__driver_single_active_trip_index.sql
-- Bounded Context: trip-service (M06 Group 3)
-- Enforces:
-- At most ONE active (non-terminal) trip per driver at any given time (FR-TRIP-007, CON-06, NFR-REL-003)
-- Prevents race conditions where a single driver could be assigned two trips concurrently.
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uk_trips_driver_single_active
    ON trips (driver_id)
    WHERE status IN ('ACCEPTED', 'PICKING_UP', 'ARRIVED', 'IN_TRIP') AND driver_id IS NOT NULL;
