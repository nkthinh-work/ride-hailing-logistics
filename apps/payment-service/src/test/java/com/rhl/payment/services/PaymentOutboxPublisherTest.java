package com.rhl.payment.services;

import com.rhl.payment.entities.PaymentOutboxEvent;
import com.rhl.payment.enums.OutboxStatus;
import com.rhl.payment.events.FinancialEventEnvelope;
import com.rhl.payment.repositories.PaymentOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentOutboxPublisher Unit Tests — Decoupled Transaction & Reliable Delivery")
class PaymentOutboxPublisherTest {

    @Mock
    private PaymentOutboxRepository outboxRepository;
    @Mock
    private PaymentOutboxTxService txService;
    @Mock
    private FinancialEventConsumer eventConsumer;

    private PaymentOutboxPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-26T15:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(txService.markSent(any(), any(), any())).thenReturn(true);
        publisher = new PaymentOutboxPublisher(
                outboxRepository,
                txService,
                eventConsumer,
                objectMapper,
                clock,
                true
        );
    }

    private PaymentOutboxEvent createEvent() {
        return new PaymentOutboxEvent(
                UUID.randomUUID(),
                "PAYMENT_SUCCEEDED",
                "PAYMENT",
                UUID.randomUUID(),
                "corr-1",
                null,
                "{\"paymentId\":\"abc\",\"amount\":50000}",
                now.minusSeconds(10)
        );
    }

    @Test
    @DisplayName("Publishes pending outbox event successfully and marks SENT via txService")
    void processPendingEvents_success_marksSent() {
        PaymentOutboxEvent event = createEvent();
        when(outboxRepository.findPendingEvents(eq(OutboxStatus.PENDING), any(), any()))
                .thenReturn(List.of(event));
        when(txService.claimEvent(eq(event.getEventId()), any(), any())).thenReturn(true);

        int published = publisher.processPendingEvents();

        assertThat(published).isEqualTo(1);
        verify(txService).claimEvent(eq(event.getEventId()), any(), any());
        verify(eventConsumer).onEvent(any(FinancialEventEnvelope.class));
        verify(txService).markSent(eq(event.getEventId()), any(), eq(now));
    }

    @Test
    @DisplayName("Skipped when event cannot be claimed (another poller claimed it)")
    void processPendingEvents_notClaimed_skips() {
        PaymentOutboxEvent event = createEvent();
        when(outboxRepository.findPendingEvents(eq(OutboxStatus.PENDING), any(), any()))
                .thenReturn(List.of(event));
        when(txService.claimEvent(eq(event.getEventId()), any(), any())).thenReturn(false);

        int published = publisher.processPendingEvents();

        assertThat(published).isEqualTo(0);
        verify(eventConsumer, never()).onEvent(any());
        verify(txService, never()).markSent(any(), any(), any());
    }

    @Test
    @DisplayName("Failure during consumption triggers retry schedule via txService")
    void processPendingEvents_consumerThrows_triggersRetry() {
        PaymentOutboxEvent event = createEvent();
        when(outboxRepository.findPendingEvents(eq(OutboxStatus.PENDING), any(), any()))
                .thenReturn(List.of(event));
        when(txService.claimEvent(eq(event.getEventId()), any(), any())).thenReturn(true);
        doThrow(new RuntimeException("Broker connection refused"))
                .when(eventConsumer).onEvent(any());

        int published = publisher.processPendingEvents();

        assertThat(published).isEqualTo(0);
        verify(txService).markFailed(eq(event.getEventId()), any(), contains("Broker connection refused"), any());
        verify(txService, never()).markSent(any(), any(), any());
    }
}
