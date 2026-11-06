package com.rhl.dispatch.services;

import com.rhl.dispatch.entities.DispatchOutboxEvent;
import com.rhl.dispatch.models.OutboxStatus;
import com.rhl.dispatch.repositories.DispatchOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DispatchOutboxTxService Unit Tests")
class DispatchOutboxTxServiceTest {

    @Mock
    private DispatchOutboxRepository outboxRepository;

    private DispatchOutboxTxService txService;

    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");

    @BeforeEach
    void setUp() {
        txService = new DispatchOutboxTxService(outboxRepository);
    }

    private DispatchOutboxEvent createEvent() {
        return new DispatchOutboxEvent(
                id,
                UUID.randomUUID(),
                "DRIVER_OFFER_CREATED",
                "DRIVER_OFFER",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "corr-1",
                "{}",
                now
        );
    }

    @Test
    @DisplayName("claimEvent should atomically update nextRetryAt when pending and return event")
    void claimEvent_shouldAtomicallyClaimWhenPending() {
        DispatchOutboxEvent event = createEvent();
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.claimEventAtomic(eq(id), eq(OutboxStatus.PENDING), eq(now), eq(claimUntil)))
                .thenReturn(1);
        when(outboxRepository.findById(id)).thenReturn(Optional.of(event));

        Optional<DispatchOutboxEvent> result = txService.claimEvent(id, now, claimUntil);

        assertThat(result).isPresent();
        verify(outboxRepository).claimEventAtomic(id, OutboxStatus.PENDING, now, claimUntil);
        verify(outboxRepository).findById(id);
    }

    @Test
    @DisplayName("claimEvent should return empty when event was already claimed by another worker")
    void claimEvent_shouldReturnEmptyWhenAlreadyClaimed() {
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.claimEventAtomic(eq(id), eq(OutboxStatus.PENDING), eq(now), eq(claimUntil)))
                .thenReturn(0);

        Optional<DispatchOutboxEvent> result = txService.claimEvent(id, now, claimUntil);

        assertThat(result).isEmpty();
        verify(outboxRepository).claimEventAtomic(id, OutboxStatus.PENDING, now, claimUntil);
        verify(outboxRepository, never()).findById(any());
    }

    @Test
    @DisplayName("markSent with fencing should update status to SENT when claim is valid")
    void markSent_withFencing_shouldUpdateStatusAndSentAt() {
        Instant claimUntil = now.plusSeconds(30);
        Instant sentAt = now.plusSeconds(2);
        when(outboxRepository.markSentAtomic(eq(id), eq(OutboxStatus.SENT), eq(OutboxStatus.PENDING), eq(claimUntil), eq(sentAt)))
                .thenReturn(1);

        boolean updated = txService.markSent(id, claimUntil, sentAt);

        assertThat(updated).isTrue();
        verify(outboxRepository).markSentAtomic(id, OutboxStatus.SENT, OutboxStatus.PENDING, claimUntil, sentAt);
    }

    @Test
    @DisplayName("markSent with fencing should reject when lease expired and reclaimed by another worker")
    void markSent_withFencing_shouldRejectWhenStale() {
        Instant claimUntil = now.plusSeconds(30);
        Instant sentAt = now.plusSeconds(35);
        when(outboxRepository.markSentAtomic(eq(id), eq(OutboxStatus.SENT), eq(OutboxStatus.PENDING), eq(claimUntil), eq(sentAt)))
                .thenReturn(0);

        boolean updated = txService.markSent(id, claimUntil, sentAt);

        assertThat(updated).isFalse();
    }

    @Test
    @DisplayName("markFailed with fencing should increment retryCount and update lastError")
    void markFailed_withFencing_shouldIncrementRetryAndSetError() {
        Instant claimUntil = now.plusSeconds(30);
        Instant retryAt = now.plusSeconds(5);
        when(outboxRepository.markFailedAtomic(eq(id), eq(OutboxStatus.PENDING), eq(claimUntil), eq("Broker timeout"), eq(retryAt)))
                .thenReturn(1);

        boolean updated = txService.markFailed(id, claimUntil, "Broker timeout", retryAt);

        assertThat(updated).isTrue();
        verify(outboxRepository).markFailedAtomic(id, OutboxStatus.PENDING, claimUntil, "Broker timeout", retryAt);
    }

    @Test
    @DisplayName("markFailed with fencing should reject when lease expired and reclaimed by another worker")
    void markFailed_withFencing_shouldRejectWhenStale() {
        Instant claimUntil = now.plusSeconds(30);
        Instant retryAt = now.plusSeconds(5);
        when(outboxRepository.markFailedAtomic(eq(id), eq(OutboxStatus.PENDING), eq(claimUntil), eq("Broker timeout"), eq(retryAt)))
                .thenReturn(0);

        boolean updated = txService.markFailed(id, claimUntil, "Broker timeout", retryAt);

        assertThat(updated).isFalse();
    }
}
