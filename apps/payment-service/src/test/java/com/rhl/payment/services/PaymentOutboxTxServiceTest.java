package com.rhl.payment.services;

import com.rhl.payment.entities.PaymentOutboxEvent;
import com.rhl.payment.enums.OutboxStatus;
import com.rhl.payment.repositories.PaymentOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentOutboxTxService Unit Tests — Isolated Short Transaction Operations")
class PaymentOutboxTxServiceTest {

    @Mock
    private PaymentOutboxRepository outboxRepository;

    private PaymentOutboxTxService txService;
    private final Instant now = Instant.parse("2026-10-26T15:00:00Z");

    @BeforeEach
    void setUp() {
        txService = new PaymentOutboxTxService(outboxRepository);
    }

    private PaymentOutboxEvent createEvent() {
        return new PaymentOutboxEvent(
                UUID.randomUUID(),
                "PAYMENT_SUCCEEDED",
                "PAYMENT",
                UUID.randomUUID(),
                "corr-1",
                null,
                "{\"paymentId\":\"abc\"}",
                now
        );
    }

    @Test
    @DisplayName("claimEvent atomically claims via conditional UPDATE and returns true")
    void claimEvent_atomic_success() {
        PaymentOutboxEvent event = createEvent();
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.claimEventAtomic(eq(event.getEventId()), eq(OutboxStatus.PENDING), any(Instant.class), eq(claimUntil)))
                .thenReturn(1);

        boolean claimed = txService.claimEvent(event.getEventId(), now, claimUntil);

        assertThat(claimed).isTrue();
        verify(outboxRepository).claimEventAtomic(eq(event.getEventId()), eq(OutboxStatus.PENDING), any(Instant.class), eq(claimUntil));
    }

    @Test
    @DisplayName("claimEvent returns false when event was already claimed by another worker")
    void claimEvent_alreadyClaimed_returnsFalse() {
        PaymentOutboxEvent event = createEvent();
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.claimEventAtomic(eq(event.getEventId()), eq(OutboxStatus.PENDING), any(Instant.class), eq(claimUntil)))
                .thenReturn(0);

        boolean claimed = txService.claimEvent(event.getEventId(), now, claimUntil);

        assertThat(claimed).isFalse();
    }

    @Test
    @DisplayName("markSent updates status to SENT with fencing")
    void markSent_withFencing_success() {
        PaymentOutboxEvent event = createEvent();
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.markSentAtomic(eq(event.getEventId()), eq(OutboxStatus.SENT), eq(now), eq(OutboxStatus.PENDING), eq(claimUntil)))
                .thenReturn(1);

        boolean sent = txService.markSent(event.getEventId(), claimUntil, now);

        assertThat(sent).isTrue();
    }

    @Test
    @DisplayName("markSent returns false when lease fencing check fails")
    void markSent_fencingFailed_returnsFalse() {
        PaymentOutboxEvent event = createEvent();
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.markSentAtomic(eq(event.getEventId()), eq(OutboxStatus.SENT), eq(now), eq(OutboxStatus.PENDING), eq(claimUntil)))
                .thenReturn(0);

        boolean sent = txService.markSent(event.getEventId(), claimUntil, now);

        assertThat(sent).isFalse();
    }

    @Test
    @DisplayName("markFailed increments retryCount and sets backoff with fencing")
    void markFailed_withFencing_success() {
        PaymentOutboxEvent event = createEvent();
        Instant claimUntil = now.plusSeconds(30);
        Instant nextRetry = now.plusSeconds(5);
        when(outboxRepository.markFailedAtomic(eq(event.getEventId()), eq("Broker down"), eq(nextRetry), eq(OutboxStatus.PENDING), eq(claimUntil)))
                .thenReturn(1);

        boolean failed = txService.markFailed(event.getEventId(), claimUntil, "Broker down", nextRetry);

        assertThat(failed).isTrue();
    }
}
