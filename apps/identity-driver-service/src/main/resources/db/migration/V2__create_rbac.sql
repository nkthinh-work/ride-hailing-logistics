
CREATE TABLE roles (
                       id UUID PRIMARY KEY,
                       code VARCHAR(50) NOT NULL UNIQUE,
                       name VARCHAR(100) NOT NULL
);

CREATE TABLE permissions (
                             id UUID PRIMARY KEY,
                             code VARCHAR(100) NOT NULL UNIQUE,
                             name VARCHAR(150) NOT NULL
);

CREATE TABLE role_permissions (
                                  role_id UUID NOT NULL,
                                  permission_id UUID NOT NULL,

                                  PRIMARY KEY (role_id, permission_id),

                                  FOREIGN KEY (role_id) REFERENCES roles(id),
                                  FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

INSERT INTO roles (id, code, name) VALUES
                                       (gen_random_uuid(), 'CUSTOMER', 'Customer'),
                                       (gen_random_uuid(), 'DRIVER', 'Driver'),
                                       (gen_random_uuid(), 'REVIEWER', 'Reviewer'),
                                       (gen_random_uuid(), 'SUPPORT_STAFF', 'Support Staff'),
                                       (gen_random_uuid(), 'FINANCE_STAFF', 'Finance Staff'),
                                       (gen_random_uuid(), 'ADMINISTRATOR', 'Administrator');

INSERT INTO permissions (id, code, name) VALUES
                                             (gen_random_uuid(), 'TRIP_CREATE', 'Create trip'),
                                             (gen_random_uuid(), 'TRIP_VIEW_OWN', 'View own trips'),
                                             (gen_random_uuid(), 'TRIP_ACCEPT', 'Accept trip'),
                                             (gen_random_uuid(), 'LOCATION_UPDATE', 'Update location'),
                                             (gen_random_uuid(), 'DRIVER_REVIEW', 'Review drivers'),
                                             (gen_random_uuid(), 'PAYMENT_REFUND', 'Refund payment'),
                                             (gen_random_uuid(), 'ROLE_MANAGE', 'Manage roles');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         JOIN permissions p ON
    (r.code = 'CUSTOMER'
        AND p.code IN ('TRIP_CREATE', 'TRIP_VIEW_OWN'))
        OR
    (r.code = 'DRIVER'
        AND p.code IN ('TRIP_ACCEPT', 'LOCATION_UPDATE'))
        OR
    (r.code = 'REVIEWER'
        AND p.code = 'DRIVER_REVIEW')
        OR
    (r.code = 'FINANCE_STAFF'
        AND p.code = 'PAYMENT_REFUND')
        OR
    (r.code = 'ADMINISTRATOR');

ALTER TABLE user_roles
    ADD COLUMN role_id UUID;

UPDATE user_roles ur
SET role_id = r.id
FROM roles r
WHERE ur.role = r.code;

DO $$
    BEGIN
        IF EXISTS (
            SELECT 1
            FROM user_roles
            WHERE role_id IS NULL
        ) THEN
            RAISE EXCEPTION 'Unknown role found in user_roles';
        END IF;
    END $$;

ALTER TABLE user_roles
    DROP CONSTRAINT user_roles_pkey;

ALTER TABLE user_roles
    DROP COLUMN role;

ALTER TABLE user_roles
    ALTER COLUMN role_id SET NOT NULL;

ALTER TABLE user_roles
    ADD PRIMARY KEY (user_id, role_id);

ALTER TABLE user_roles
    ADD CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id) REFERENCES roles(id);
