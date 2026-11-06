-- Flyway Migration V3: Assignment Resilience and Matching Session Invariants
-- Authoritative Database: rhl_dispatch (PostgreSQL 15)

-- 1. Replace strict ACCEPTED unique index with assignment-aware resilient winner index.
-- If an offer's trip assignment fails (assignment_status = 'FAILED'), the trip winner slot is released
-- to allow rematching or candidate progression, without deleting audit history or violating one-winner invariant.
DROP INDEX IF EXISTS uk_driver_offers_single_winning_accepted;

CREATE UNIQUE INDEX uk_driver_offers_single_winning_assigned
    ON driver_offers (trip_id)
    WHERE status = 'ACCEPTED' AND assignment_status IN ('PENDING', 'ASSIGNED');

-- 2. Invariant: Exactly at most ONE active matching session (SEARCHING) per trip
CREATE UNIQUE INDEX uk_matching_sessions_active_trip
    ON matching_sessions (trip_id)
    WHERE status = 'SEARCHING';

-- 3. Add optimistic locking version column to matching_sessions
ALTER TABLE matching_sessions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
