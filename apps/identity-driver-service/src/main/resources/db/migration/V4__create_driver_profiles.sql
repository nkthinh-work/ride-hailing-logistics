
CREATE TABLE driver_profiles (
                                 id UUID PRIMARY KEY,

                                 user_id UUID NOT NULL UNIQUE,

                                 full_name VARCHAR(120) NOT NULL,
                                 phone VARCHAR(20) NOT NULL,
                                 driver_license_number VARCHAR(50) NOT NULL UNIQUE,
                                 vehicle_plate VARCHAR(20) NOT NULL,

                                 status VARCHAR(20) NOT NULL,

                                 rejection_reason VARCHAR(500),

                                 created_at TIMESTAMPTZ NOT NULL,
                                 reviewed_at TIMESTAMPTZ,
                                 reviewed_by UUID,

                                 CONSTRAINT fk_driver_profiles_user
                                     FOREIGN KEY (user_id) REFERENCES users(id),

                                 CONSTRAINT fk_driver_profiles_reviewer
                                     FOREIGN KEY (reviewed_by) REFERENCES users(id),

                                 CONSTRAINT ck_driver_profiles_status
                                     CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_driver_profiles_status
    ON driver_profiles(status);
