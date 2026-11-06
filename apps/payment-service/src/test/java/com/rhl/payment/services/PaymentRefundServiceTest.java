package com.rhl.payment.services;

import com.rhl.payment.dtos.CreateRefundRequest;
import com.rhl.payment.dtos.RefundEligibilityResponse;
import com.rhl.payment.dtos.RefundResponse;
import com.rhl.payment.entities.PaymentOutboxEvent;
import com.rhl.payment.entities.PaymentRefund;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentAttemptStatus;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.RefundStatus;
import com.rhl.payment.exceptions.IdempotencyConflictException;
import com.rhl.payment.exceptions.OverRefundException;
import com.rhl.payment.providers.PaymentGatewayProvider;
import com.rhl.payment.providers.ProviderRefundCommand;
import com.rhl.payment.providers.ProviderRefundResult;
import com.rhl.payment.repositories.PaymentAttemptRepository;
import com.rhl.payment.repositories.PaymentOutboxRepository;
import com.rhl.payment.repositories.PaymentRefundRepository;
import com.rhl.payment.repositories.PaymentTransactionRepository;
import com.rhl.payment.repositories.RefundAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRefundService Unit Tests — Monetary Safety & Idempotency")
class PaymentRefundServiceTest {

    @Mock
    private PaymentRefundRepository refundRepository;
    @Mock
    private RefundAttemptRepository refundAttemptRepository;
    @Mock
    private PaymentTransactionRepository paymentRepository;
    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;
    @Mock
    private PaymentOutboxRepository outboxRepository;
    @Mock
    private PaymentGatewayProvider momoProvider;

    private PaymentRefundService refundService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID customerId = UUID.randomUUID();
    private final UUID financeStaffId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(momoProvider.getProviderName()).thenReturn("MOMO");
        refundService = new PaymentRefundService(
                refundRepository,
                refundAttemptRepository,
                paymentRepository,
                paymentAttemptRepository,
                outboxRepository,
                List.of(momoProvider),
                momoProvider,
                objectMapper,
                true,   // policyGated = true
                false   // autoApprove = false
        );
    }

    private PaymentTransaction createSucceededPayment(PaymentMethod method, BigDecimal amount) {
        Instant now = Instant.now();
        PaymentTransaction tx = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(amount)
                .currency("VND")
                .paymentMethod(method)
                .idempotencyKey("idemp-payment-1")
                .now(now)
                .initialActor(customerId.toString(), "CUSTOMER")
                .build();
        tx.markSucceeded("TRANS-9988", "MOMO", "PROVIDER", now);
        return tx;
    }

    @Test
    @DisplayName("Eligibility Check: Returns true with remaining balance for SUCCEEDED payment")
    void checkRefundEligibility_succeededPayment_eligible() {
        PaymentTransaction payment = createSucceededPayment(PaymentMethod.DIGITAL_WALLET, new BigDecimal("100000.00"));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.sumAmountByPaymentIdAndStatusIn(eq(paymentId), any())).thenReturn(BigDecimal.ZERO);

        RefundEligibilityResponse response = refundService.checkRefundEligibility(customerId, paymentId, false);

        assertThat(response.eligible()).isTrue();
        assertThat(response.remainingRefundableAmount()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(response.currency()).isEqualTo("VND");
        assertThat(response.policyMode()).isEqualTo("REFUND_POLICY_PENDING");
    }

    @Test
    @DisplayName("Eligibility Check: Returns false for non-SUCCEEDED payment")
    void checkRefundEligibility_pendingPayment_notEligible() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("idemp-2")
                .now(Instant.now())
                .initialActor(customerId.toString(), "CUSTOMER")
                .build();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        RefundEligibilityResponse response = refundService.checkRefundEligibility(customerId, paymentId, false);

        assertThat(response.eligible()).isFalse();
        assertThat(response.remainingRefundableAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Over-Refund Invariant: Request exceeding remaining balance throws OverRefundException")
    void requestRefund_exceedingRemaining_throwsOverRefundException() {
        PaymentTransaction payment = createSucceededPayment(PaymentMethod.DIGITAL_WALLET, new BigDecimal("100000.00"));
        when(refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, "idemp-ref-1")).thenReturn(Optional.empty());
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        // Already committed 80,000 VND
        when(refundRepository.sumAmountByPaymentIdAndStatusIn(eq(paymentId), any()))
                .thenReturn(new BigDecimal("80000.00"));

        CreateRefundRequest request = new CreateRefundRequest(new BigDecimal("30000.00"), "Over-refund test");

        assertThatThrownBy(() -> refundService.requestRefund(customerId, paymentId, "idemp-ref-1", request, false))
                .isInstanceOf(OverRefundException.class)
                .hasMessageContaining("exceeds remaining refundable amount");
    }

    @Test
    @DisplayName("Idempotency Replay: Duplicate request key returns existing refund without re-processing")
    void requestRefund_duplicateKey_returnsExistingReplay() {
        PaymentRefund existing = PaymentRefund.builder()
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .reason("Duplicate test")
                .idempotencyKey("idemp-ref-dup")
                .requestedBy(customerId.toString())
                .actorRole("CUSTOMER")
                .now(Instant.now())
                .build();

        when(refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, "idemp-ref-dup"))
                .thenReturn(Optional.of(existing));

        CreateRefundRequest request = new CreateRefundRequest(new BigDecimal("50000.00"), "Duplicate test");
        RefundResponse response = refundService.requestRefund(customerId, paymentId, "idemp-ref-dup", request, false);

        assertThat(response.refundId()).isEqualTo(existing.getId());
        verify(paymentRepository, never()).findByIdForUpdate(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("Idempotency Conflict: Same key with differing amount throws IdempotencyConflictException")
    void requestRefund_sameKeyDifferingAmount_throwsConflict() {
        PaymentRefund existing = PaymentRefund.builder()
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .reason("Original test")
                .idempotencyKey("idemp-ref-conflict")
                .requestedBy(customerId.toString())
                .actorRole("CUSTOMER")
                .now(Instant.now())
                .build();

        when(refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, "idemp-ref-conflict"))
                .thenReturn(Optional.of(existing));

        CreateRefundRequest request = new CreateRefundRequest(new BigDecimal("70000.00"), "Differing amount");

        assertThatThrownBy(() -> refundService.requestRefund(customerId, paymentId, "idemp-ref-conflict", request, false))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("CASH Refund Flow: Creates refund in REQUESTED state with CASH_REFUND_SETTLEMENT_PENDING note")
    void requestRefund_cashPayment_setsSettlementPending() {
        PaymentTransaction payment = createSucceededPayment(PaymentMethod.CASH, new BigDecimal("60000.00"));
        when(refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, "idemp-cash-ref")).thenReturn(Optional.empty());
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.sumAmountByPaymentIdAndStatusIn(eq(paymentId), any())).thenReturn(BigDecimal.ZERO);
        when(refundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateRefundRequest request = new CreateRefundRequest(new BigDecimal("60000.00"), "Wrong charge");
        RefundResponse response = refundService.requestRefund(customerId, paymentId, "idemp-cash-ref", request, false);

        assertThat(response.status()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(response.policyNote()).isEqualTo("CASH_REFUND_SETTLEMENT_PENDING");
        assertThat(response.provider()).isEqualTo("CASH");

        // Outbox event must be created
        verify(outboxRepository).save(any(PaymentOutboxEvent.class));
        // External MoMo provider must NOT be called for CASH
        verify(momoProvider, never()).createRefund(any());
    }

    @Test
    @DisplayName("Digital Refund Approval: Finance staff approves and executes provider refund")
    void approveAndExecuteRefund_digitalPayment_callsProvider() {
        Instant now = Instant.now();
        PaymentRefund refund = PaymentRefund.builder()
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .reason("Driver cancelled")
                .idempotencyKey("idemp-approve")
                .requestedBy(customerId.toString())
                .actorRole("CUSTOMER")
                .provider("MOMO")
                .initialStatus(RefundStatus.REQUESTED)
                .now(now)
                .build();

        when(refundRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(refundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(momoProvider.createRefund(any(ProviderRefundCommand.class)))
                .thenReturn(new ProviderRefundResult(
                        "REF-12345",
                        "MOMO-REF-9999",
                        PaymentAttemptStatus.SUCCEEDED,
                        "0",
                        "Refund processed successfully"
                ));

        RefundResponse response = refundService.approveAndExecuteRefund(financeStaffId, refund.getId(), "Staff approved");

        assertThat(response.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(response.providerRefundTransactionId()).isEqualTo("MOMO-REF-9999");

        // Verify outbox was published
        ArgumentCaptor<PaymentOutboxEvent> outboxCaptor = ArgumentCaptor.forClass(PaymentOutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("PAYMENT_REFUNDED");
    }

    @Test
    @DisplayName("Digital Refund Approval: Blocked when policy is REFUND_POLICY_PENDING")
    void approveAndExecuteRefund_policyPending_throwsRefundNotEligibleException() {
        Instant now = Instant.now();
        PaymentRefund refund = PaymentRefund.builder()
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .reason("Customer request")
                .idempotencyKey("idemp-pending-policy")
                .requestedBy(customerId.toString())
                .actorRole("CUSTOMER")
                .provider("MOMO")
                .policyNote("REFUND_POLICY_PENDING")
                .initialStatus(RefundStatus.REQUESTED)
                .now(now)
                .build();

        when(refundRepository.findById(refund.getId())).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> refundService.approveAndExecuteRefund(financeStaffId, refund.getId(), "Staff approve attempt"))
                .isInstanceOf(com.rhl.payment.exceptions.RefundNotEligibleException.class)
                .hasMessageContaining("REFUND_POLICY_PENDING");

        verify(momoProvider, never()).createRefund(any());
    }
}
