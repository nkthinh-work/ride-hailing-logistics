package com.rhl.identity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IdentityDriverServiceApplicationTests {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    static {
        postgres.start();
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Spring ApplicationContext starts up cleanly with isolated Testcontainer DataSource")
    void contextLoads() {
        assertThat(dataSource).isNotNull();
        assertThat(flyway).isNotNull();
    }

    @Test
    @DisplayName("Flyway migrations V1..V6 are applied sequentially to target schema version 6")
    void flywayMigrationsAppliedSuccessfully() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
        assertThat(flyway.info().applied()).hasSize(6);
    }

    @Test
    @DisplayName("Database schema contains all required tables from migrations V1..V6")
    void databaseSchemaCreatedWithExpectedTables() throws Exception {
        List<String> tableNames = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(connection.getCatalog(), "public", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tableNames.add(rs.getString("TABLE_NAME"));
                }
            }
        }

        assertThat(tableNames).contains(
                "users",
                "user_roles",
                "roles",
                "permissions",
                "role_permissions",
                "refresh_sessions",
                "driver_profiles",
                "vehicles",
                "driver_audit_logs"
        );
    }
}
