-- Flyway Migration V2: Offer Assignment Status Tracking
-- Resolves permanent driver exclusion by distinguishing pending trip assignment from historical accepted offers

ALTER TABLE driver_offers ADD COLUMN assignment_status VARCHAR(32) NOT NULL DEFAULT 'UNASSIGNED';
ALTER TABLE driver_offers ADD COLUMN assigned_at TIMESTAMP WITH TIME ZONE;

-- Check constraint for valid assignment statuses
ALTER TABLE driver_offers ADD CONSTRAINT chk_driver_offers_assignment_status
    CHECK (assignment_status IN ('UNASSIGNED', 'PENDING', 'ASSIGNED', 'FAILED'));

-- For existing historical ACCEPTED offers from V1, mark them as ASSIGNED
UPDATE driver_offers SET assignment_status = 'ASSIGNED' WHERE status = 'ACCEPTED';

-- Performance lookup index for active assignment conflicts
CREATE INDEX idx_driver_offers_driver_assignment ON driver_offers (driver_id, status, assignment_status);
