package com.rhl.dispatch;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf("isDockerAvailable")
@DisplayName("Flyway V1 Schema Verification on PostgreSQL Testcontainer")
class DispatchFlywaySchemaTest {

    static PostgreSQLContainer postgres;

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (isDockerAvailable()) {
            if (postgres == null) {
                postgres = new PostgreSQLContainer("postgres:15-alpine");
                postgres.start();
            }
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }
    }

    @Autowired(required = false)
    private Flyway flyway;

    @Autowired(required = false)
    private DataSource dataSource;

    @Test
    @DisplayName("Flyway applies V1, V2, V3, and V4 migrations successfully to PostgreSQL Testcontainer")
    void flywayMigrationAppliedSuccessfully() {
        assertThat(flyway).isNotNull();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(flyway.info().applied()).hasSize(4);
    }

    @Test
    @DisplayName("PostgreSQL schema contains driver_offers, driver_offer_history, matching_sessions, and dispatch_outbox_events tables")
    void databaseSchemaContainsExpectedTables() throws Exception {
        assertThat(dataSource).isNotNull();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = metaData.getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).contains("driver_offers", "driver_offer_history", "matching_sessions", "dispatch_outbox_events");

            List<String> columns = new ArrayList<>();
            try (ResultSet rs = metaData.getColumns(null, "public", "driver_offers", "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
            assertThat(columns).contains("assignment_status", "assigned_at");

            List<String> sessionColumns = new ArrayList<>();
            try (ResultSet rs = metaData.getColumns(null, "public", "matching_sessions", "%")) {
                while (rs.next()) {
                    sessionColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
            assertThat(sessionColumns).contains("version");
        }
    }
}
