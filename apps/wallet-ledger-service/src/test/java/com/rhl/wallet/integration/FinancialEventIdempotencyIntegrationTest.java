package com.rhl.wallet.integration;

import com.rhl.wallet.domain.FinancialInboxEvent;
import com.rhl.wallet.domain.InboxStatus;
import com.rhl.wallet.dtos.FinancialEventIngestRequest;
import com.rhl.wallet.dtos.FinancialEventIngestResponse;
import com.rhl.wallet.exceptions.DuplicateFinancialEventException;
import com.rhl.wallet.repositories.FinancialInboxEventRepository;
import com.rhl.wallet.services.FinancialEventIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf("isDockerAvailable")
@DisplayName("Financial Event Idempotency & Conflict Guard on PostgreSQL Testcontainers")
class FinancialEventIdempotencyIntegrationTest {

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

    @Autowired
    private FinancialEventIngestionService ingestionService;

    @Autowired
    private FinancialInboxEventRepository inboxRepository;

    @Test
    @DisplayName("Ingest event: Persists inbox record with PENDING_POLICY and prevents duplicate processing")
    void ingestEvent_persistsInboxAndHandlesReplay() {
        UUID eventId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        String payload = "{\"paymentMethod\":\"CASH\",\"amount\":100000.00,\"status\":\"SUCCEEDED\"}";

        FinancialEventIngestRequest firstRequest = new FinancialEventIngestRequest(
                eventId,
                "PAYMENT_SUCCEEDED",
                "payment-service",
                entityId,
                "corr-int-idemp-1",
                payload
        );

        // First attempt: should succeed and record as PENDING_POLICY
        FinancialEventIngestResponse firstResponse = ingestionService.ingestEvent(firstRequest);
        assertThat(firstResponse.status()).isEqualTo("PENDING_POLICY");

        Optional<FinancialInboxEvent> saved = inboxRepository.findByEventId(eventId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(InboxStatus.PENDING_POLICY);

        // Second attempt (identical payload): should return DUPLICATE replay without creating duplicate rows
        FinancialEventIngestResponse secondResponse = ingestionService.ingestEvent(firstRequest);
        assertThat(secondResponse.status()).isEqualTo("DUPLICATE");

        // Third attempt with differing payload: should throw DuplicateFinancialEventException
        FinancialEventIngestRequest conflictRequest = new FinancialEventIngestRequest(
                eventId,
                "PAYMENT_SUCCEEDED",
                "payment-service",
                entityId,
                "corr-int-idemp-1",
                "{\"paymentMethod\":\"DIGITAL_WALLET\",\"amount\":999999.00}"
        );

        assertThatThrownBy(() -> ingestionService.ingestEvent(conflictRequest))
                .isInstanceOf(DuplicateFinancialEventException.class);
    }
}
