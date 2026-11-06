package com.rhl.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class FlywaySchemaVerificationTest {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    static {
        postgres.start();
    }

    @Autowired
    private DataSource dataSource;

    private UUID createTestUser(Connection conn, String email) throws SQLException {
        UUID userId = UUID.randomUUID();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO users (id, email, password_hash, status, created_at) " +
                        "VALUES (?, ?, 'hash', 'ACTIVE', ?)")) {
            stmt.setObject(1, userId);
            stmt.setString(2, email);
            stmt.setTimestamp(3, Timestamp.from(Instant.now()));
            stmt.executeUpdate();
        }
        return userId;
    }

    private UUID createTestDriverProfile(Connection conn, UUID userId, String licenseNo, String status) throws SQLException {
        UUID profileId = UUID.randomUUID();
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO driver_profiles (id, user_id, full_name, phone, driver_license_number, vehicle_plate, status, created_at) " +
                        "VALUES (?, ?, 'Test Driver', '0901234567', ?, '29A-12345', ?, ?)")) {
            stmt.setObject(1, profileId);
            stmt.setObject(2, userId);
            stmt.setString(3, licenseNo);
            stmt.setString(4, status);
            stmt.setTimestamp(5, Timestamp.from(Instant.now()));
            stmt.executeUpdate();
        }
        return profileId;
    }

    @Nested
    @DisplayName("V5 Vehicles Schema & Constraint Validation")
    class VehicleConstraintTests {

        @Test
        @DisplayName("Inserting vehicle with non-existent driver_profile_id violates fk_vehicles_driver_profile")
        void foreignKey_nonExistentDriverProfile_fails() throws Exception {
            try (Connection conn = dataSource.getConnection()) {
                UUID fakeProfileId = UUID.randomUUID();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO vehicles (id, driver_profile_id, type, plate, make, model, color, status, created_at) " +
                                "VALUES (?, ?, 'CAR_4_SEAT', '30A-99999', 'Toyota', 'Vios', 'White', 'ACTIVE', ?)")) {
                    stmt.setObject(1, UUID.randomUUID());
                    stmt.setObject(2, fakeProfileId);
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()));

                    assertThatThrownBy(stmt::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("fk_vehicles_driver_profile");
                }
            }
        }

        @Test
        @DisplayName("Duplicate vehicle plate violates unique constraint")
        void uniqueConstraint_duplicatePlate_fails() throws Exception {
            try (Connection conn = dataSource.getConnection()) {
                UUID userId = createTestUser(conn, "veh_user_" + UUID.randomUUID() + "@test.com");
                UUID profileId = createTestDriverProfile(conn, userId, "LIC_" + UUID.randomUUID(), "APPROVED");

                String plate = "51F-" + (System.nanoTime() % 100000);

                // First vehicle insert succeeds
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO vehicles (id, driver_profile_id, type, plate, make, model, color, status, created_at) " +
                                "VALUES (?, ?, 'CAR_4_SEAT', ?, 'Toyota', 'Vios', 'White', 'ACTIVE', ?)")) {
                    stmt.setObject(1, UUID.randomUUID());
                    stmt.setObject(2, profileId);
                    stmt.setString(3, plate);
                    stmt.setTimestamp(4, Timestamp.from(Instant.now()));
                    stmt.executeUpdate();
                }

                // Second vehicle insert with identical plate must fail with unique constraint violation
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO vehicles (id, driver_profile_id, type, plate, make, model, color, status, created_at) " +
                                "VALUES (?, ?, 'MOTORBIKE', ?, 'Honda', 'Wave', 'Red', 'ACTIVE', ?)")) {
                    stmt.setObject(1, UUID.randomUUID());
                    stmt.setObject(2, profileId);
                    stmt.setString(3, plate);
                    stmt.setTimestamp(4, Timestamp.from(Instant.now()));

                    assertThatThrownBy(stmt::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("vehicles_plate_key");
                }
            }
        }

        @Test
        @DisplayName("Invalid vehicle type violates ck_vehicles_type check constraint")
        void checkConstraint_invalidVehicleType_fails() throws Exception {
            try (Connection conn = dataSource.getConnection()) {
                UUID userId = createTestUser(conn, "veh_type_" + UUID.randomUUID() + "@test.com");
                UUID profileId = createTestDriverProfile(conn, userId, "LIC_" + UUID.randomUUID(), "APPROVED");

                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO vehicles (id, driver_profile_id, type, plate, make, model, color, status, created_at) " +
                                "VALUES (?, ?, 'AIRPLANE', 'VN-A123', 'Boeing', '777', 'White', 'ACTIVE', ?)")) {
                    stmt.setObject(1, UUID.randomUUID());
                    stmt.setObject(2, profileId);
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()));

                    assertThatThrownBy(stmt::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("ck_vehicles_type");
                }
            }
        }
    }

    @Nested
    @DisplayName("V6 Driver Suspension & Audit Trail Validation")
    class SuspensionAndAuditTests {

        @Test
        @DisplayName("Driver profile status accepts SUSPENDED per V6 constraint update")
        void driverProfile_acceptsSuspendedStatus() throws Exception {
            try (Connection conn = dataSource.getConnection()) {
                UUID userId = createTestUser(conn, "susp_user_" + UUID.randomUUID() + "@test.com");
                UUID profileId = createTestDriverProfile(conn, userId, "LIC_" + UUID.randomUUID(), "SUSPENDED");

                assertThat(profileId).isNotNull();
            }
        }

        @Test
        @DisplayName("Driver audit logs require valid driver_profile_id and actor_id foreign keys")
        void auditLog_foreignKeysEnforced() throws Exception {
            try (Connection conn = dataSource.getConnection()) {
                UUID fakeProfileId = UUID.randomUUID();
                UUID fakeActorId = UUID.randomUUID();

                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO driver_audit_logs (id, driver_profile_id, actor_id, action, previous_status, new_status, reason, created_at) " +
                                "VALUES (?, ?, ?, 'SUSPEND', 'APPROVED', 'SUSPENDED', 'Policy breach', ?)")) {
                    stmt.setObject(1, UUID.randomUUID());
                    stmt.setObject(2, fakeProfileId);
                    stmt.setObject(3, fakeActorId);
                    stmt.setTimestamp(4, Timestamp.from(Instant.now()));

                    assertThatThrownBy(stmt::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("fk_driver_audit_logs");
                }
            }
        }

        @Test
        @DisplayName("Driver audit log action check constraint rejects unknown actions")
        void auditLog_checkConstraintRejectsUnknownAction() throws Exception {
            try (Connection conn = dataSource.getConnection()) {
                UUID userId = createTestUser(conn, "act_user_" + UUID.randomUUID() + "@test.com");
                UUID profileId = createTestDriverProfile(conn, userId, "LIC_" + UUID.randomUUID(), "APPROVED");

                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO driver_audit_logs (id, driver_profile_id, actor_id, action, previous_status, new_status, reason, created_at) " +
                                "VALUES (?, ?, ?, 'DELETE_USER', 'APPROVED', 'REJECTED', 'Hacked', ?)")) {
                    stmt.setObject(1, UUID.randomUUID());
                    stmt.setObject(2, profileId);
                    stmt.setObject(3, userId);
                    stmt.setTimestamp(4, Timestamp.from(Instant.now()));

                    assertThatThrownBy(stmt::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("ck_driver_audit_logs_action");
                }
            }
        }
    }
}
