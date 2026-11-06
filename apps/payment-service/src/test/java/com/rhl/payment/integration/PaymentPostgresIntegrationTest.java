package com.rhl.payment.integration;

import com.rhl.payment.entities.PaymentStatusHistory;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;
import com.rhl.payment.repositories.PaymentStatusHistoryRepository;
import com.rhl.payment.repositories.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf("isDockerAvailable")
class PaymentPostgresIntegrationTest {

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
    private PaymentTransactionRepository paymentRepository;

    @Autowired(required = false)
    private PaymentStatusHistoryRepository historyRepository;

    @Autowired(required = false)
    private com.rhl.payment.repositories.PaymentAttemptRepository attemptRepository;

    @Autowired(required = false)
    private com.rhl.payment.repositories.PaymentRefundRepository refundRepository;

    @Autowired(required = false)
    private com.rhl.payment.repositories.RefundAttemptRepository refundAttemptRepository;

    @Autowired(required = false)
    private com.rhl.payment.repositories.PaymentOutboxRepository outboxRepository;

    private final UUID customerId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    @BeforeEach
    void clean() {
        if (outboxRepository != null) {
            outboxRepository.deleteAll();
        }
        if (refundAttemptRepository != null) {
            refundAttemptRepository.deleteAll();
        }
        if (refundRepository != null) {
            refundRepository.deleteAll();
        }
        if (attemptRepository != null) {
            attemptRepository.deleteAll();
        }
        if (historyRepository != null) {
            historyRepository.deleteAll();
        }
        if (paymentRepository != null) {
            paymentRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("DB Unique Constraint: uk_payments_customer_idempotency prevents duplicate (customer, key)")
    void duplicateCustomerIdempotencyKeyThrowsException() {
        String key = "idemp-key-db-test";

        PaymentTransaction tx1 = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("40000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey(key)
                .build();
        paymentRepository.saveAndFlush(tx1);

        PaymentTransaction tx2 = PaymentTransaction.builder()
                .tripId(UUID.randomUUID())
                .customerId(customerId) // same customer
                .amount(new BigDecimal("40000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey(key) // same key
                .build();

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(tx2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Partial Index: uk_payments_trip_single_success prevents double charging for same trip")
    void doubleChargingPreventedByPartialUniqueIndex() {
        PaymentTransaction tx1 = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("70000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("key-cash")
                .build();
        tx1.markSucceeded("CASH-REF", "driver", "DRIVER", Instant.now());
        paymentRepository.saveAndFlush(tx1);

        // Attempting to record a second SUCCEEDED payment for the same trip
        PaymentTransaction tx2 = PaymentTransaction.builder()
                .tripId(tripId) // same trip
                .customerId(customerId)
                .amount(new BigDecimal("70000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-wallet")
                .build();
        tx2.markSucceeded("WALLET-REF", "payment-service", "INTERNAL_SERVICE", Instant.now());

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(tx2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Allows multiple FAILED/CANCELLED transactions followed by a SUCCEEDED payment for same trip")
    void allowsFailedAttemptFollowedBySuccess() {
        PaymentTransaction failedTx = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.CREDIT_CARD)
                .idempotencyKey("key-card-attempt-1")
                .build();
        failedTx.startProcessing("actor", "CUSTOMER", "dispatched", Instant.now());
        failedTx.markFailed("Card expired", "gateway", "INTERNAL_SERVICE", Instant.now());
        paymentRepository.saveAndFlush(failedTx);

        PaymentTransaction successTx = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-wallet-attempt-2")
                .build();
        successTx.startProcessing("actor", "CUSTOMER", "dispatched", Instant.now());
        successTx.markSucceeded("WALLET-OK", "gateway", "INTERNAL_SERVICE", Instant.now());
        paymentRepository.saveAndFlush(successTx);

        List<PaymentTransaction> tripPayments = paymentRepository.findByTripId(tripId);
        assertThat(tripPayments).hasSize(2);
        assertThat(paymentRepository.existsByTripIdAndStatus(tripId, PaymentStatus.SUCCEEDED)).isTrue();
    }

    @Test
    @DisplayName("Cascades and persists append-only status history atomically with aggregate")
    void persistsStatusHistoryAtomically() {
        PaymentTransaction tx = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("25000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("key-audit-test")
                .initialActor(customerId.toString(), "CUSTOMER")
                .build();

        tx.startProcessing(customerId.toString(), "CUSTOMER", "Processing started", Instant.now());
        tx.markSucceeded("CASH-COLLECTED", "driver-42", "DRIVER", Instant.now());

        PaymentTransaction saved = paymentRepository.saveAndFlush(tx);

        List<PaymentStatusHistory> history = historyRepository.findByPaymentIdOrderByCreatedAtAsc(saved.getId());
        assertThat(history).hasSize(3);
        assertThat(history.get(0).getToStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(history.get(1).getToStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(history.get(2).getToStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(history.get(2).getActor()).isEqualTo("driver-42");
        assertThat(history.get(2).getActorRole()).isEqualTo("DRIVER");
    }

    @Test
    @DisplayName("DB Partial Index: uk_payments_trip_single_active prevents two concurrent active transactions for same trip")
    void doubleActiveProcessingTransactionsPreventedByPartialUniqueIndex() {
        PaymentTransaction tx1 = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("key-active-1")
                .build();
        tx1.startProcessing("cust", "CUSTOMER", "Processing 1", Instant.now());
        paymentRepository.saveAndFlush(tx1);

        PaymentTransaction tx2 = PaymentTransaction.builder()
                .tripId(tripId) // same trip
                .customerId(customerId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-active-2") // different key
                .build();
        tx2.startProcessing("cust", "CUSTOMER", "Processing 2", Instant.now());

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(tx2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Partial Index: uk_payment_attempts_single_active prevents two concurrent active attempts for same payment")
    void doubleActivePaymentAttemptsPreventedByPartialUniqueIndex() {
        PaymentTransaction tx = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("80000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-attempt-db")
                .build();
        PaymentTransaction savedTx = paymentRepository.saveAndFlush(tx);

        com.rhl.payment.entities.PaymentAttempt attempt1 = com.rhl.payment.entities.PaymentAttempt.builder()
                .paymentId(savedTx.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId("momo-ord-1")
                .amount(new BigDecimal("80000.00"))
                .currency("VND")
                .idempotencyReference("idemp-ref-1")
                .status(com.rhl.payment.enums.PaymentAttemptStatus.PROCESSING)
                .build();
        attemptRepository.saveAndFlush(attempt1);

        com.rhl.payment.entities.PaymentAttempt attempt2 = com.rhl.payment.entities.PaymentAttempt.builder()
                .paymentId(savedTx.getId()) // same payment obligation
                .attemptNumber(2)
                .provider("MOMO")
                .providerOrderId("momo-ord-2")
                .amount(new BigDecimal("80000.00"))
                .currency("VND")
                .idempotencyReference("idemp-ref-2")
                .status(com.rhl.payment.enums.PaymentAttemptStatus.PROCESSING) // concurrent processing attempt
                .build();

        assertThatThrownBy(() -> attemptRepository.saveAndFlush(attempt2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Partial Index: uk_payment_attempts_single_active prevents new active attempt when previous is UNKNOWN (V4)")
    void unknownPaymentAttemptPreventsDuplicateActiveAttempts() {
        PaymentTransaction tx = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("85000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-attempt-unknown-db")
                .build();
        PaymentTransaction savedTx = paymentRepository.saveAndFlush(tx);

        com.rhl.payment.entities.PaymentAttempt attemptUnknown = com.rhl.payment.entities.PaymentAttempt.builder()
                .paymentId(savedTx.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId("momo-ord-unknown-1")
                .amount(new BigDecimal("85000.00"))
                .currency("VND")
                .idempotencyReference("idemp-ref-unknown-1")
                .status(com.rhl.payment.enums.PaymentAttemptStatus.UNKNOWN)
                .build();
        attemptRepository.saveAndFlush(attemptUnknown);

        com.rhl.payment.entities.PaymentAttempt attemptNew = com.rhl.payment.entities.PaymentAttempt.builder()
                .paymentId(savedTx.getId()) // same payment obligation
                .attemptNumber(2)
                .provider("MOMO")
                .providerOrderId("momo-ord-new-2")
                .amount(new BigDecimal("85000.00"))
                .currency("VND")
                .idempotencyReference("idemp-ref-new-2")
                .status(com.rhl.payment.enums.PaymentAttemptStatus.PENDING) // attempting new active attempt
                .build();

        assertThatThrownBy(() -> attemptRepository.saveAndFlush(attemptNew))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Unique Constraint: uk_payment_attempts_provider_order prevents duplicate provider order ID")
    void duplicateProviderOrderIdPreventedByUniqueConstraint() {
        PaymentTransaction tx1 = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("30000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-tx-1")
                .build();
        PaymentTransaction savedTx1 = paymentRepository.saveAndFlush(tx1);

        com.rhl.payment.entities.PaymentAttempt attempt1 = com.rhl.payment.entities.PaymentAttempt.builder()
                .paymentId(savedTx1.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId("MOMO-DUPLICATE-ORDER-123")
                .amount(new BigDecimal("30000.00"))
                .currency("VND")
                .idempotencyReference("ref-1")
                .status(com.rhl.payment.enums.PaymentAttemptStatus.PENDING)
                .build();
        attemptRepository.saveAndFlush(attempt1);

        PaymentTransaction tx2 = PaymentTransaction.builder()
                .tripId(UUID.randomUUID())
                .customerId(customerId)
                .amount(new BigDecimal("30000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-tx-2")
                .build();
        PaymentTransaction savedTx2 = paymentRepository.saveAndFlush(tx2);

        com.rhl.payment.entities.PaymentAttempt attempt2 = com.rhl.payment.entities.PaymentAttempt.builder()
                .paymentId(savedTx2.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId("MOMO-DUPLICATE-ORDER-123") // duplicate order ID
                .amount(new BigDecimal("30000.00"))
                .currency("VND")
                .idempotencyReference("ref-2")
                .status(com.rhl.payment.enums.PaymentAttemptStatus.PENDING)
                .build();

        assertThatThrownBy(() -> attemptRepository.saveAndFlush(attempt2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Unique Constraint: uk_refunds_payment_idempotency prevents duplicate refund idempotency key")
    void duplicateRefundIdempotencyKeyPrevented() {
        PaymentTransaction tx = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-tx-refund-test")
                .build();
        PaymentTransaction savedTx = paymentRepository.saveAndFlush(tx);

        com.rhl.payment.entities.PaymentRefund refund1 = com.rhl.payment.entities.PaymentRefund.builder()
                .id(UUID.randomUUID())
                .paymentId(savedTx.getId())
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("40000.00"))
                .currency("VND")
                .reason("First refund")
                .idempotencyKey("refund-key-dup-test")
                .requestedBy(customerId.toString())
                .build();
        refundRepository.saveAndFlush(refund1);

        com.rhl.payment.entities.PaymentRefund refund2 = com.rhl.payment.entities.PaymentRefund.builder()
                .id(UUID.randomUUID())
                .paymentId(savedTx.getId())
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("40000.00"))
                .currency("VND")
                .reason("Duplicate refund attempt")
                .idempotencyKey("refund-key-dup-test")
                .requestedBy(customerId.toString())
                .build();

        assertThatThrownBy(() -> refundRepository.saveAndFlush(refund2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("PostgreSQL Aggregate Query: sumAmountByPaymentIdAndStatusIn correctly aggregates refund balances")
    void sumAmountByPaymentIdAndStatusIn_aggregatesCorrectly() {
        PaymentTransaction tx = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-tx-sum-test")
                .build();
        PaymentTransaction savedTx = paymentRepository.saveAndFlush(tx);

        com.rhl.payment.entities.PaymentRefund refund1 = com.rhl.payment.entities.PaymentRefund.builder()
                .id(UUID.randomUUID())
                .paymentId(savedTx.getId())
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("30000.00"))
                .currency("VND")
                .reason("Partial refund 1")
                .idempotencyKey("ref-key-1")
                .requestedBy(customerId.toString())
                .initialStatus(com.rhl.payment.enums.RefundStatus.SUCCEEDED)
                .build();
        refundRepository.saveAndFlush(refund1);

        com.rhl.payment.entities.PaymentRefund refund2 = com.rhl.payment.entities.PaymentRefund.builder()
                .id(UUID.randomUUID())
                .paymentId(savedTx.getId())
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("25000.00"))
                .currency("VND")
                .reason("Partial refund 2")
                .idempotencyKey("ref-key-2")
                .requestedBy(customerId.toString())
                .initialStatus(com.rhl.payment.enums.RefundStatus.PROCESSING)
                .build();
        refundRepository.saveAndFlush(refund2);

        BigDecimal committed = refundRepository.sumAmountByPaymentIdAndStatusIn(
                savedTx.getId(),
                java.util.Set.of(com.rhl.payment.enums.RefundStatus.SUCCEEDED, com.rhl.payment.enums.RefundStatus.PROCESSING)
        );

        assertThat(committed).isEqualByComparingTo(new BigDecimal("55000.00"));
    }

    @Test
    @DisplayName("PostgreSQL Outbox Persistence: Saves PaymentOutboxEvent atomically with pending status")
    void outboxEvent_persistsSuccessfullyInPostgres() {
        com.rhl.payment.entities.PaymentOutboxEvent event = new com.rhl.payment.entities.PaymentOutboxEvent(
                UUID.randomUUID(),
                "PAYMENT_SUCCEEDED",
                "PAYMENT",
                UUID.randomUUID(),
                "corr-123",
                customerId,
                "{\"paymentId\":\"test\",\"amount\":100000}",
                Instant.now()
        );

        com.rhl.payment.entities.PaymentOutboxEvent saved = outboxRepository.saveAndFlush(event);
        assertThat(saved.getEventId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(com.rhl.payment.enums.OutboxStatus.PENDING);
        assertThat(saved.getVersion()).isEqualTo(0L);
    }
}
