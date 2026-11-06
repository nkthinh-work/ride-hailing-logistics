
-- Migration V6: Driver Suspension & Audit Trail
-- 1. Update check constraint on driver_profiles to allow SUSPENDED status
ALTER TABLE driver_profiles DROP CONSTRAINT ck_driver_profiles_status;
ALTER TABLE driver_profiles ADD CONSTRAINT ck_driver_profiles_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'));

-- 2. Create append-only table driver_audit_logs
CREATE TABLE driver_audit_logs (
    id UUID PRIMARY KEY,
    driver_profile_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    action VARCHAR(50) NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_driver_audit_logs_profile
        FOREIGN KEY (driver_profile_id) REFERENCES driver_profiles(id),

    CONSTRAINT fk_driver_audit_logs_actor
        FOREIGN KEY (actor_id) REFERENCES users(id),

    CONSTRAINT ck_driver_audit_logs_action
        CHECK (action IN ('APPROVE', 'REJECT', 'SUSPEND', 'REACTIVATE'))
);

CREATE INDEX idx_driver_audit_logs_profile
    ON driver_audit_logs(driver_profile_id);

CREATE INDEX idx_driver_audit_logs_created_at
    ON driver_audit_logs(created_at);

-- 3. Add DRIVER_SUSPEND permission and assign to ADMINISTRATOR role
INSERT INTO permissions (id, code, name) VALUES
    (gen_random_uuid(), 'DRIVER_SUSPEND', 'Suspend or reactivate drivers');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMINISTRATOR'
  AND p.code = 'DRIVER_SUSPEND'
ON CONFLICT (role_id, permission_id) DO NOTHING;
