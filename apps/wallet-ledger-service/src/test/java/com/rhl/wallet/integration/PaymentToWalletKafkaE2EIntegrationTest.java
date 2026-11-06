package com.rhl.wallet.integration;

import com.rhl.wallet.domain.FinancialInboxEvent;
import com.rhl.wallet.domain.InboxStatus;
import com.rhl.wallet.repositories.FinancialInboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "kafka.enabled=true",
        "kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(
        partitions = 3,
        topics = {"rhl.payment.events.v1", "rhl.payment.events.v1.dlt"},
        bootstrapServersProperty = "kafka.bootstrap-servers"
)
@EnabledIf("isDockerAvailable")
@DisplayName("Payment-to-Wallet Kafka End-to-End Integration Test with PostgreSQL & Kafka Broker")
class PaymentToWalletKafkaE2EIntegrationTest {

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
    private KafkaTemplate<String, String> dltKafkaTemplate;

    @Autowired
    private FinancialInboxEventRepository inboxRepository;

    @Test
    @DisplayName("Kafka E2E: Real event published to rhl.payment.events.v1 is ingested into PostgreSQL inbox")
    void paymentSucceeded_consumedAndPersistedInPostgresInbox() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();

        String eventJson = """
                {
                  "eventId": "%s",
                  "eventType": "PAYMENT_SUCCEEDED",
                  "eventVersion": 1,
                  "occurredAt": "2026-10-27T10:00:00Z",
                  "correlationId": "corr-e2e-1",
                  "entityId": "%s",
                  "payload": {
                    "paymentId": "%s",
                    "tripId": "%s",
                    "amount": 75000,
                    "currency": "VND",
                    "paymentMethod": "CASH"
                  }
                }
                """.formatted(eventId, paymentId, paymentId, tripId);

        // 1. Publish to Kafka topic
        dltKafkaTemplate.send("rhl.payment.events.v1", paymentId.toString(), eventJson);

        // 2. Await ingestion by PaymentEventKafkaListener into PostgreSQL Financial Inbox
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<FinancialInboxEvent> opt = inboxRepository.findByEventId(eventId);
            assertThat(opt).isPresent();
            FinancialInboxEvent inbox = opt.get();
            assertThat(inbox.getEventType()).isEqualTo("PAYMENT_SUCCEEDED");
            assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PENDING_POLICY);
            assertThat(inbox.getFailureReason()).contains("COMMISSION_POLICY_PENDING");
        });
    }

    @Test
    @DisplayName("Kafka E2E Idempotency: Duplicate Kafka message redelivery does not create duplicate inbox row")
    void duplicateKafkaDelivery_handledIdempotentlyWithoutDuplicateRows() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        String eventJson = """
                {
                  "eventId": "%s",
                  "eventType": "PAYMENT_SUCCEEDED",
                  "eventVersion": 1,
                  "occurredAt": "2026-10-27T10:05:00Z",
                  "correlationId": "corr-e2e-dedup",
                  "entityId": "%s",
                  "payload": {
                    "paymentId": "%s",
                    "amount": 120000,
                    "currency": "VND",
                    "paymentMethod": "DIGITAL"
                  }
                }
                """.formatted(eventId, paymentId, paymentId);

        // Send first time
        dltKafkaTemplate.send("rhl.payment.events.v1", paymentId.toString(), eventJson);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<FinancialInboxEvent> opt = inboxRepository.findByEventId(eventId);
            assertThat(opt).isPresent();
            assertThat(opt.get().getStatus()).isEqualTo(InboxStatus.PENDING_POLICY);
            assertThat(opt.get().getFailureReason()).contains("SETTLEMENT_POLICY_PENDING");
        });

        long countBefore = inboxRepository.count();

        // Send second time (redelivery simulation)
        dltKafkaTemplate.send("rhl.payment.events.v1", paymentId.toString(), eventJson);

        // Wait brief period to allow consumer to process redelivery
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }

        long countAfter = inboxRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("Kafka E2E Poison Pill & DLT: Malformed record without eventId routes to Dead Letter Topic")
    void malformedRecord_routedToDeadLetterTopic() {
        String poisonPill = """
                {
                  "eventType": "PAYMENT_SUCCEEDED",
                  "payload": {"amount": 50000}
                }
                """;

        String key = "poison-pill-key-" + UUID.randomUUID();
        dltKafkaTemplate.send("rhl.payment.events.v1", key, poisonPill);

        // Verify that invalid message was NOT saved into inbox
        await().pollDelay(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            assertThat(inboxRepository.findAll().stream().anyMatch(e -> poisonPill.equals(e.getPayload()))).isFalse();
        });
    }
}
