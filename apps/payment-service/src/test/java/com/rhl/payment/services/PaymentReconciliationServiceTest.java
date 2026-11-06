package com.rhl.payment.services;

import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.entities.PaymentOutboxEvent;
import com.rhl.payment.entities.PaymentReconciliationRecord;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentAttemptStatus;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;
import com.rhl.payment.enums.ReconciliationMismatchType;
import com.rhl.payment.enums.ReconciliationResolutionState;
import com.rhl.payment.providers.PaymentGatewayProvider;
import com.rhl.payment.providers.ProviderQueryResult;
import com.rhl.payment.repositories.PaymentAttemptRepository;
import com.rhl.payment.repositories.PaymentOutboxRepository;
import com.rhl.payment.repositories.PaymentReconciliationRepository;
import com.rhl.payment.repositories.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentReconciliationService Unit Tests — Authoritative Gateway Query & Mismatch Safety")
class PaymentReconciliationServiceTest {

    @Mock
    private PaymentAttemptRepository attemptRepository;
    @Mock
    private PaymentTransactionRepository paymentRepository;
    @Mock
    private PaymentReconciliationRepository reconciliationRepository;
    @Mock
    private PaymentOutboxRepository outboxRepository;
    @Mock
    private PaymentGatewayProvider momoProvider;

    private PaymentReconciliationService reconciliationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-26T12:00:00Z");
    private final Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

    private final UUID paymentId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(momoProvider.getProviderName()).thenReturn("MOMO");
        reconciliationService = new PaymentReconciliationService(
                attemptRepository,
                paymentRepository,
                reconciliationRepository,
                outboxRepository,
                List.of(momoProvider),
                momoProvider,
                objectMapper,
                fixedClock,
                true,
                15 // 15 seconds stale threshold
        );
    }

    private PaymentTransaction createPayment(PaymentStatus status) {
        return PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("75000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("idemp-rec-1")
                .now(now.minusSeconds(60))
                .initialActor(customerId.toString(), "CUSTOMER")
                .build();
    }

    private PaymentAttempt createAttempt(PaymentAttemptStatus status) {
        return PaymentAttempt.builder()
                .id(UUID.randomUUID())
                .paymentId(paymentId)
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId("ORD-REC-1001")
                .amount(new BigDecimal("75000.00"))
                .currency("VND")
                .idempotencyReference("idemp-rec-1")
                .status(status)
                .now(now.minusSeconds(60))
                .build();
    }

    @Test
    @DisplayName("Reconciliation resolves stale PROCESSING attempt to SUCCEEDED when provider confirms success")
    void reconcile_providerConfirmedSuccess_resolvesPaymentToSucceeded() {
        PaymentAttempt attempt = createAttempt(PaymentAttemptStatus.PROCESSING);
        PaymentTransaction payment = createPayment(PaymentStatus.PROCESSING);
        payment.startProcessing(customerId.toString(), "CUSTOMER", "dispatched", now.minusSeconds(60));

        when(attemptRepository.findByStatusInAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(attempt));
        when(attemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(momoProvider.queryTransaction("ORD-REC-1001"))
                .thenReturn(new ProviderQueryResult(
                        "ORD-REC-1001",
                        "MOMO-TRANS-8899",
                        PaymentAttemptStatus.SUCCEEDED,
                        "0",
                        "Successful"
                ));

        int processed = reconciliationService.reconcileStalePendingAttempts();

        assertThat(processed).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        verify(attemptRepository).save(attempt);
        verify(paymentRepository).save(payment);

        // Outbox event must be published
        ArgumentCaptor<PaymentOutboxEvent> outboxCaptor = ArgumentCaptor.forClass(PaymentOutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("PAYMENT_SUCCEEDED");
    }

    @Test
    @DisplayName("Mismatch Safety: Flags LOCAL_FAILED_PROVIDER_SUCCESS when local is FAILED but provider is SUCCEEDED")
    void reconcile_localFailedProviderSuccess_flagsCriticalMismatch() {
        PaymentAttempt attempt = createAttempt(PaymentAttemptStatus.PROCESSING);
        PaymentTransaction payment = createPayment(PaymentStatus.FAILED);
        payment.markFailed("Timeout during checkout", "SYSTEM", "SYSTEM", now.minusSeconds(30));

        when(attemptRepository.findByStatusInAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(attempt));
        when(attemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(momoProvider.queryTransaction("ORD-REC-1001"))
                .thenReturn(new ProviderQueryResult(
                        "ORD-REC-1001",
                        "MOMO-TRANS-CRITICAL",
                        PaymentAttemptStatus.SUCCEEDED,
                        "0",
                        "Successful"
                ));

        int processed = reconciliationService.reconcileStalePendingAttempts();

        assertThat(processed).isEqualTo(1);
        // Payment status must NOT be silently overwritten since FAILED is terminal
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        // Mismatch record must be saved in UNRESOLVED state
        ArgumentCaptor<PaymentReconciliationRecord> recordCaptor = ArgumentCaptor.forClass(PaymentReconciliationRecord.class);
        verify(reconciliationRepository).save(recordCaptor.capture());
        PaymentReconciliationRecord savedRecord = recordCaptor.getValue();
        assertThat(savedRecord.getMismatchType()).isEqualTo(ReconciliationMismatchType.LOCAL_FAILED_PROVIDER_SUCCESS);
        assertThat(savedRecord.getResolutionState()).isEqualTo(ReconciliationResolutionState.FLAGGED_MANUAL_REVIEW);

        // Reconciliation flagged outbox event must be published
        ArgumentCaptor<PaymentOutboxEvent> outboxCaptor = ArgumentCaptor.forClass(PaymentOutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("PAYMENT_RECONCILIATION_FLAGGED");
    }

    @Test
    @DisplayName("Provider query timeout or UNKNOWN leaves attempt unchanged without premature failure")
    void reconcile_providerQueryUnknown_leavesAttemptInProcessing() {
        PaymentAttempt attempt = createAttempt(PaymentAttemptStatus.PROCESSING);
        PaymentTransaction payment = createPayment(PaymentStatus.PROCESSING);
        payment.startProcessing(customerId.toString(), "CUSTOMER", "dispatched", now.minusSeconds(60));

        when(attemptRepository.findByStatusInAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(attempt));
        when(attemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(momoProvider.queryTransaction("ORD-REC-1001"))
                .thenReturn(new ProviderQueryResult(
                        "ORD-REC-1001",
                        null,
                        PaymentAttemptStatus.UNKNOWN,
                        "99",
                        "Gateway timeout"
                ));

        int processed = reconciliationService.reconcileStalePendingAttempts();

        assertThat(processed).isEqualTo(1);
        // Attempt transitions to UNKNOWN, remaining non-terminal without premature failure
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        verify(reconciliationRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }
}
