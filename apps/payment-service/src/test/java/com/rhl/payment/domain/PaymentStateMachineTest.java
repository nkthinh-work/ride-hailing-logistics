package com.rhl.payment.domain;

import com.rhl.payment.enums.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStateMachineTest {

    @Test
    @DisplayName("PENDING state allows transition to PROCESSING, CANCELLED, FAILED, SUCCEEDED")
    void pendingAllowedTransitions() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PROCESSING)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.CANCELLED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.SUCCEEDED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("PROCESSING state allows transition to SUCCEEDED, FAILED, CANCELLED")
    void processingAllowedTransitions() {
        assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.SUCCEEDED)).isTrue();
        assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.CANCELLED)).isTrue();
        assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("FAILED state allows retry to PROCESSING or CANCELLED, but not directly to SUCCEEDED")
    void failedAllowedTransitions() {
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.PROCESSING)).isTrue();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.CANCELLED)).isTrue();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.SUCCEEDED)).isFalse();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("SUCCEEDED and CANCELLED are terminal states and reject any transition")
    void terminalStatesRejectAll() {
        assertThat(PaymentStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(PaymentStatus.CANCELLED.isTerminal()).isTrue();

        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.SUCCEEDED.canTransitionTo(target)).isFalse();
            assertThat(PaymentStatus.CANCELLED.canTransitionTo(target)).isFalse();
        }
    }
}
