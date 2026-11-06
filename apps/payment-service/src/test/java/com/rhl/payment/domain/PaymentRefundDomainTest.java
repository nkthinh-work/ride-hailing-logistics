package com.rhl.payment.domain;

import com.rhl.payment.entities.PaymentRefund;
import com.rhl.payment.enums.RefundStatus;
import com.rhl.payment.exceptions.InvalidPaymentAmountException;
import com.rhl.payment.exceptions.InvalidPaymentStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PaymentRefund Domain State Machine & Monetary Invariants")
class PaymentRefundDomainTest {

    private final UUID refundId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    @Test
    @DisplayName("Successfully creates PaymentRefund in REQUESTED state with initial audit entry")
    void createPaymentRefund_success() {
        Instant now = Instant.now();
        PaymentRefund refund = PaymentRefund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .reason("Driver did not arrive")
                .idempotencyKey("idemp-ref-1")
                .requestedBy(customerId.toString())
                .actorRole("CUSTOMER")
                .now(now)
                .build();

        assertThat(refund.getId()).isEqualTo(refundId);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(refund.getCurrency()).isEqualTo("VND");
        assertThat(refund.getStatusHistory()).hasSize(1);
        assertThat(refund.getStatusHistory().get(0).getNewStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    @DisplayName("Non-positive refund amount throws InvalidPaymentAmountException")
    void nonPositiveAmount_throwsException() {
        assertThatThrownBy(() -> PaymentRefund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(BigDecimal.ZERO)
                .reason("Driver did not arrive")
                .idempotencyKey("idemp-ref-1")
                .requestedBy(customerId.toString())
                .build())
                .isInstanceOf(InvalidPaymentAmountException.class);
    }

    @Test
    @DisplayName("State machine transitions: REQUESTED -> APPROVED -> PROCESSING -> SUCCEEDED")
    void fullRefundLifecycle_succeeded() {
        Instant now = Instant.now();
        PaymentRefund refund = PaymentRefund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .reason("Trip cancelled by driver")
                .idempotencyKey("idemp-ref-2")
                .requestedBy(customerId.toString())
                .actorRole("CUSTOMER")
                .now(now)
                .build();

        // 1. Approve
        String staffId = UUID.randomUUID().toString();
        refund.approve(staffId, "Verified customer complaint", now.plusSeconds(10));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(refund.getApprovedBy()).isEqualTo(staffId);

        // 2. Start Processing
        refund.startProcessing("REF-ORD-12345", "SYSTEM", "SYSTEM", "Dispatched to MoMo", now.plusSeconds(20));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refund.getProviderRefundOrderId()).isEqualTo("REF-ORD-12345");

        // 3. Mark Succeeded
        refund.markSucceeded("MOMO-TRANS-9988", "MOMO", "PROVIDER", now.plusSeconds(30));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.getProviderRefundTransactionId()).isEqualTo("MOMO-TRANS-9988");
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(refund.getStatus().isTerminal()).isTrue();

        // Audit entries must record all 4 steps
        assertThat(refund.getStatusHistory()).hasSize(4);
    }

    @Test
    @DisplayName("Cannot transition from terminal SUCCEEDED to FAILED or any other state")
    void terminalSucceeded_cannotRevert() {
        Instant now = Instant.now();
        PaymentRefund refund = PaymentRefund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .reason("Test")
                .idempotencyKey("idemp-ref-3")
                .requestedBy(customerId.toString())
                .now(now)
                .build();

        refund.approve("staff-1", "ok", now);
        refund.startProcessing("ORD-1", "sys", "sys", "dispatch", now);
        refund.markSucceeded("TRANS-1", "prov", "prov", now);

        assertThatThrownBy(() -> refund.markFailed("Late failure callback", "MOMO", "PROVIDER", now))
                .isInstanceOf(InvalidPaymentStateTransitionException.class);
    }

    @Test
    @DisplayName("Can transition to FAILED from PROCESSING")
    void processing_canTransitionToFailed() {
        Instant now = Instant.now();
        PaymentRefund refund = PaymentRefund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .reason("Test")
                .idempotencyKey("idemp-ref-4")
                .requestedBy(customerId.toString())
                .now(now)
                .build();

        refund.startProcessing("ORD-FAIL", "SYSTEM", "SYSTEM", "dispatch", now);
        refund.markFailed("Provider declined: Insufficient balance", "MOMO", "PROVIDER", now);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getStatus().isTerminal()).isTrue();
    }
}
