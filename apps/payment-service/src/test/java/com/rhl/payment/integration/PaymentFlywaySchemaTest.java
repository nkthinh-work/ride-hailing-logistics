package com.rhl.payment.integration;

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
class PaymentFlywaySchemaTest {

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
    @DisplayName("PostgreSQL schema contains transactions, attempts, refunds, outbox, and reconciliation tables with unique indexes")
    void databaseSchemaContainsExpectedTablesAndIndexes() throws Exception {
        assertThat(dataSource).isNotNull();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = metaData.getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).contains(
                    "payment_transactions",
                    "payment_status_history",
                    "payment_attempts",
                    "payment_refunds",
                    "refund_attempts",
                    "refund_status_history",
                    "payment_outbox_events",
                    "payment_reconciliation_records"
            );

            List<String> paymentIndexes = new ArrayList<>();
            try (ResultSet rs = metaData.getIndexInfo(null, "public", "payment_transactions", false, false)) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    if (indexName != null) {
                        paymentIndexes.add(indexName);
                    }
                }
            }
            assertThat(paymentIndexes).contains(
                    "uk_payments_customer_idempotency",
                    "uk_payments_trip_single_success",
                    "uk_payments_trip_single_active"
            );

            List<String> attemptIndexes = new ArrayList<>();
            try (ResultSet rs = metaData.getIndexInfo(null, "public", "payment_attempts", false, false)) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    if (indexName != null) {
                        attemptIndexes.add(indexName);
                    }
                }
            }
            assertThat(attemptIndexes).contains(
                    "uk_payment_attempts_provider_order",
                    "uk_payment_attempts_single_active"
            );

            List<String> refundIndexes = new ArrayList<>();
            try (ResultSet rs = metaData.getIndexInfo(null, "public", "payment_refunds", false, false)) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    if (indexName != null) {
                        refundIndexes.add(indexName);
                    }
                }
            }
            assertThat(refundIndexes).contains(
                    "uk_refunds_payment_idempotency"
            );
        }
    }
}
