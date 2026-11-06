package com.rhl.dispatch.services;

import com.rhl.dispatch.clients.RealtimePublishClient;
import com.rhl.dispatch.entities.DispatchOutboxEvent;
import com.rhl.dispatch.events.DispatchOfferPayload;
import com.rhl.dispatch.models.OutboxStatus;
import com.rhl.dispatch.repositories.DispatchOutboxRepository;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DispatchOutboxPublisher Unit Tests")
class DispatchOutboxPublisherTest {

    @Mock
    private DispatchOutboxRepository outboxRepository;

    @Mock
    private DispatchOutboxTxService txService;

    @Mock
    private KafkaDispatchEventPublisher kafkaPublisher;

    @Mock
    private RealtimePublishClient realtimePublishClient;

    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private DispatchOutboxPublisher publisher;

    private final UUID eventId = UUID.randomUUID();
    private final UUID offerId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-26T11:00:00Z");

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        org.mockito.Mockito.lenient().when(txService.markSent(any(), any(), any())).thenReturn(true);
        publisher = new DispatchOutboxPublisher(
                outboxRepository,
                txService,
                kafkaPublisher,
                realtimePublishClient,
                objectMapper,
                fixedClock,
                true,
                "kafka",
                30000
        );
    }

    private DispatchOutboxEvent createTestEvent() throws Exception {
        DispatchOfferPayload payload = new DispatchOfferPayload(
                offerId,
                tripId,
                driverId,
                "RIDE",
                10.5,
                106.6,
                "Pickup Address",
                "Dropoff Address",
                5.0,
                BigDecimal.valueOf(50000),
                now,
                now.plusSeconds(15),
                "CREATED"
        );
        String json = objectMapper.writeValueAsString(payload);

        return new DispatchOutboxEvent(
                UUID.randomUUID(),
                eventId,
                "DRIVER_OFFER_CREATED",
                "DRIVER_OFFER",
                offerId,
                driverId,
                "corr-offer-" + offerId,
                json,
                now
        );
    }

    @Test
    @DisplayName("Should successfully publish to Kafka and mark SENT via txService")
    void shouldProcessPendingEventsSuccessfullyViaKafka() throws Exception {
        DispatchOutboxEvent event = createTestEvent();
        when(outboxRepository.findPendingEvents(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(txService.claimEvent(eq(event.getId()), any(Instant.class), any(Instant.class)))
                .thenReturn(Optional.of(event));

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("rhl.dispatch.offer.v1", 0), 0, 0, 0, 0, 0
        );
        when(kafkaPublisher.publishOfferEvent(eq(tripId), any())).thenReturn(metadata);

        int processed = publisher.processPendingEvents();

        assertThat(processed).isEqualTo(1);
        verify(txService).claimEvent(eq(event.getId()), any(Instant.class), any(Instant.class));
        verify(kafkaPublisher).publishOfferEvent(eq(tripId), any());
        verify(txService).markSent(eq(event.getId()), any(Instant.class), eq(now));
        verifyNoInteractions(realtimePublishClient);
    }

    @Test
    @DisplayName("Should publish via legacy-http when transport is configured to legacy-http")
    void shouldPublishViaLegacyHttpWhenConfigured() throws Exception {
        DispatchOutboxPublisher httpPublisher = new DispatchOutboxPublisher(
                outboxRepository,
                txService,
                kafkaPublisher,
                realtimePublishClient,
                objectMapper,
                fixedClock,
                true,
                "legacy-http",
                30000
        );

        DispatchOutboxEvent event = createTestEvent();
        when(outboxRepository.findPendingEvents(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(txService.claimEvent(eq(event.getId()), any(Instant.class), any(Instant.class)))
                .thenReturn(Optional.of(event));

        int processed = httpPublisher.processPendingEvents();

        assertThat(processed).isEqualTo(1);
        verify(realtimePublishClient).publishOffer(eq(driverId), any());
        verify(txService).markSent(eq(event.getId()), any(Instant.class), eq(now));
        verifyNoInteractions(kafkaPublisher);
    }

    @Test
    @DisplayName("Should handle Kafka failure and record markFailed with backoff via txService")
    void shouldHandleFailureWithExponentialBackoff() throws Exception {
        DispatchOutboxEvent event = createTestEvent();
        when(outboxRepository.findPendingEvents(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(txService.claimEvent(eq(event.getId()), any(Instant.class), any(Instant.class)))
                .thenReturn(Optional.of(event));

        doThrow(new RuntimeException("Kafka broker unreachable"))
                .when(kafkaPublisher).publishOfferEvent(eq(tripId), any());

        int processed = publisher.processPendingEvents();

        assertThat(processed).isEqualTo(0);
        verify(txService, never()).markSent(any(), any(), any());
        verify(txService).markFailed(eq(event.getId()), any(Instant.class), contains("Kafka broker unreachable"), any(Instant.class));
    }

    @Test
    @DisplayName("Should skip processing when publisher is disabled")
    void shouldSkipWhenDisabled() {
        DispatchOutboxPublisher disabledPublisher = new DispatchOutboxPublisher(
                outboxRepository,
                txService,
                kafkaPublisher,
                realtimePublishClient,
                objectMapper,
                fixedClock,
                false,
                "kafka",
                30000
        );

        disabledPublisher.scheduledProcess();

        verifyNoInteractions(outboxRepository);
        verifyNoInteractions(txService);
        verifyNoInteractions(kafkaPublisher);
        verifyNoInteractions(realtimePublishClient);
    }
}
