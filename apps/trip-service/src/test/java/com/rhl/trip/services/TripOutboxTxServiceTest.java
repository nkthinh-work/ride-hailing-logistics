package com.rhl.trip.services;

import com.rhl.trip.entities.TripOutboxEvent;
import com.rhl.trip.models.OutboxStatus;
import com.rhl.trip.repositories.TripOutboxRepository;
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
@DisplayName("TripOutboxTxService Unit Tests")
class TripOutboxTxServiceTest {

    @Mock
    private TripOutboxRepository outboxRepository;

    private TripOutboxTxService txService;

    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");

    @BeforeEach
    void setUp() {
        txService = new TripOutboxTxService(outboxRepository);
    }

    private TripOutboxEvent createEvent() {
        return new TripOutboxEvent(
                id,
                UUID.randomUUID(),
                "TRIP_ACCEPTED",
                "TRIP",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CUSTOMER",
                "corr-1",
                "{}",
                now
        );
    }

    @Test
    @DisplayName("claimEvent should atomically claim via conditional UPDATE and return event")
    void claimEvent_shouldAtomicallyClaimAndReturn() {
        TripOutboxEvent event = createEvent();
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.claimEventAtomic(eq(id), eq(OutboxStatus.PENDING), any(Instant.class), eq(claimUntil)))
                .thenReturn(1);
        when(outboxRepository.findById(id)).thenReturn(Optional.of(event));

        Optional<TripOutboxEvent> result = txService.claimEvent(id, now, claimUntil);

        assertThat(result).isPresent();
        verify(outboxRepository).claimEventAtomic(eq(id), eq(OutboxStatus.PENDING), any(Instant.class), eq(claimUntil));
    }

    @Test
    @DisplayName("claimEvent should return empty when event is already claimed")
    void claimEvent_shouldReturnEmptyWhenAlreadyClaimed() {
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.claimEventAtomic(eq(id), eq(OutboxStatus.PENDING), any(Instant.class), eq(claimUntil)))
                .thenReturn(0);

        Optional<TripOutboxEvent> result = txService.claimEvent(id, now, claimUntil);

        assertThat(result).isEmpty();
        verify(outboxRepository, never()).findById(any());
    }

    @Test
    @DisplayName("markSent should update status to SENT with fencing")
    void markSent_shouldUpdateStatusWithFencing() {
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.markSentAtomic(eq(id), eq(OutboxStatus.SENT), eq(now), eq(OutboxStatus.PENDING), eq(claimUntil)))
                .thenReturn(1);

        boolean updated = txService.markSent(id, claimUntil, now);

        assertThat(updated).isTrue();
    }

    @Test
    @DisplayName("markSent should return false when fencing fails due to expired lease")
    void markSent_shouldReturnFalseWhenFencingFails() {
        Instant claimUntil = now.plusSeconds(30);
        when(outboxRepository.markSentAtomic(eq(id), eq(OutboxStatus.SENT), eq(now), eq(OutboxStatus.PENDING), eq(claimUntil)))
                .thenReturn(0);

        boolean updated = txService.markSent(id, claimUntil, now);

        assertThat(updated).isFalse();
    }

    @Test
    @DisplayName("markFailed should update error with fencing")
    void markFailed_shouldUpdateErrorWithFencing() {
        Instant claimUntil = now.plusSeconds(30);
        Instant retryAt = now.plusSeconds(5);
        when(outboxRepository.markFailedAtomic(eq(id), eq("Broker timeout"), eq(retryAt), eq(OutboxStatus.PENDING), eq(claimUntil)))
                .thenReturn(1);

        boolean updated = txService.markFailed(id, claimUntil, "Broker timeout", retryAt);

        assertThat(updated).isTrue();
    }
}
