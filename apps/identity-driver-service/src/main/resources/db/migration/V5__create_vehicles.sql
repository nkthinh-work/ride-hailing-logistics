
CREATE TABLE vehicles (
    id UUID PRIMARY KEY,
    driver_profile_id UUID NOT NULL,
    type VARCHAR(30) NOT NULL,
    plate VARCHAR(20) NOT NULL UNIQUE,
    make VARCHAR(50) NOT NULL,
    model VARCHAR(50) NOT NULL,
    color VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,

    CONSTRAINT fk_vehicles_driver_profile
        FOREIGN KEY (driver_profile_id) REFERENCES driver_profiles(id),

    CONSTRAINT ck_vehicles_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),

    CONSTRAINT ck_vehicles_type
        CHECK (type IN ('MOTORBIKE', 'CAR_4_SEAT', 'CAR_7_SEAT'))
);

CREATE INDEX idx_vehicles_driver_profile
    ON vehicles(driver_profile_id);

CREATE INDEX idx_vehicles_plate
    ON vehicles(plate);

-- Backfill from existing driver_profiles while preserving driver_profiles.vehicle_plate
INSERT INTO vehicles (id, driver_profile_id, type, plate, make, model, color, status, created_at)
SELECT
    gen_random_uuid(),
    dp.id,
    'MOTORBIKE',
    UPPER(TRIM(dp.vehicle_plate)),
    'Standard',
    'Standard',
    'Black',
    CASE WHEN dp.status = 'APPROVED' THEN 'ACTIVE' ELSE 'INACTIVE' END,
    dp.created_at
FROM driver_profiles dp
ON CONFLICT (plate) DO NOTHING;
