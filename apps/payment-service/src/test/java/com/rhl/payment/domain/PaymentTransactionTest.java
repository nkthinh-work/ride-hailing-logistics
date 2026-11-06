package com.rhl.payment.domain;

import com.rhl.payment.entities.PaymentStatusHistory;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;
import com.rhl.payment.exceptions.InvalidPaymentAmountException;
import com.rhl.payment.exceptions.InvalidPaymentStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTransactionTest {

    private final UUID tripId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @Test
    @DisplayName("Successfully creates PaymentTransaction with valid amount and initial audit entry")
    void createValidPaymentTransaction() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-123")
                .initialActor(customerId.toString(), "CUSTOMER")
                .build();

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getTripId()).isEqualTo(tripId);
        assertThat(payment.getCustomerId()).isEqualTo(customerId);
        assertThat(payment.getAmount()).isEqualByComparingTo("50000.00");
        assertThat(payment.getCurrency()).isEqualTo("VND");
        assertThat(payment.getPaymentMethod()).isEqualTo(PaymentMethod.DIGITAL_WALLET);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getIdempotencyKey()).isEqualTo("key-123");

        // Verify initial audit log entry
        List<PaymentStatusHistory> history = payment.getStatusHistory();
        assertThat(history).hasSize(1);
        PaymentStatusHistory initial = history.get(0);
        assertThat(initial.getFromStatus()).isNull();
        assertThat(initial.getToStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(initial.getActor()).isEqualTo(customerId.toString());
        assertThat(initial.getActorRole()).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("Throws InvalidPaymentAmountException when amount is zero or negative")
    void rejectNonPositiveAmount() {
        assertThatThrownBy(() -> PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(BigDecimal.ZERO)
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("key-0")
                .build())
                .isInstanceOf(InvalidPaymentAmountException.class);

        assertThatThrownBy(() -> PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("-1000"))
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("key-neg")
                .build())
                .isInstanceOf(InvalidPaymentAmountException.class);
    }

    @Test
    @DisplayName("Monetary amount scale is normalized to 2 decimal places")
    void monetaryAmountScaleNormalized() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("12345.678"))
                .paymentMethod(PaymentMethod.CREDIT_CARD)
                .idempotencyKey("key-scale")
                .build();

        assertThat(payment.getAmount()).isEqualTo(new BigDecimal("12345.68"));
        assertThat(payment.getAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Valid FSM transitions: PENDING -> PROCESSING -> SUCCEEDED with audit records")
    void validFsmSuccessPath() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("35000.00"))
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-fsm-success")
                .build();

        Instant t1 = Instant.now();
        payment.startProcessing(customerId.toString(), "CUSTOMER", "Initiated with wallet provider", t1);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.getStatusHistory()).hasSize(2);

        Instant t2 = t1.plusSeconds(2);
        payment.markSucceeded("MOMO-TX-998877", "payment-service", "INTERNAL_SERVICE", t2);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderReference()).isEqualTo("MOMO-TX-998877");
        assertThat(payment.getCompletedAt()).isEqualTo(t2);
        assertThat(payment.getStatusHistory()).hasSize(3);

        PaymentStatusHistory lastAudit = payment.getStatusHistory().get(2);
        assertThat(lastAudit.getFromStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(lastAudit.getToStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Valid FSM transitions: PENDING -> PROCESSING -> FAILED")
    void validFsmFailurePath() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("35000.00"))
                .paymentMethod(PaymentMethod.CREDIT_CARD)
                .idempotencyKey("key-fsm-fail")
                .build();

        payment.startProcessing(customerId.toString(), "CUSTOMER", "Dispatched to card gateway", Instant.now());
        payment.markFailed("Card declined: insufficient funds", "payment-service", "INTERNAL_SERVICE", Instant.now());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Card declined: insufficient funds");
        assertThat(payment.getStatusHistory()).hasSize(3);
    }

    @Test
    @DisplayName("Terminal state invariant: SUCCEEDED transaction cannot regress or transition")
    void terminalStateSucceededCannotTransition() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("key-terminal")
                .build();

        payment.markSucceeded("CASH-COLLECTED", "driver-1", "DRIVER", Instant.now());
        assertThat(payment.getStatus().isTerminal()).isTrue();

        assertThatThrownBy(() -> payment.markFailed("Error", "actor", "ROLE", Instant.now()))
                .isInstanceOf(InvalidPaymentStateTransitionException.class);

        assertThatThrownBy(() -> payment.cancel("Cancel", "actor", "ROLE", Instant.now()))
                .isInstanceOf(InvalidPaymentStateTransitionException.class);
    }

    @Test
    @DisplayName("Terminal state invariant: CANCELLED transaction cannot transition")
    void terminalStateCancelledCannotTransition() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("key-cancelled")
                .build();

        payment.cancel("Customer cancelled", customerId.toString(), "CUSTOMER", Instant.now());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getStatus().isTerminal()).isTrue();

        assertThatThrownBy(() -> payment.markSucceeded("REF", "actor", "ROLE", Instant.now()))
                .isInstanceOf(InvalidPaymentStateTransitionException.class);
    }
}
