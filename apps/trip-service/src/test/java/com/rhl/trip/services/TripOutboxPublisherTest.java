package com.rhl.trip.services;

import com.rhl.trip.clients.RealtimeServiceClient;
import com.rhl.trip.entities.TripOutboxEvent;
import com.rhl.trip.events.TripStatusPayload;
import com.rhl.trip.models.OutboxStatus;
import com.rhl.trip.repositories.TripOutboxRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TripOutboxPublisher Unit Tests")
class TripOutboxPublisherTest {

    @Mock
    private TripOutboxRepository outboxRepository;

    @Mock
    private TripOutboxTxService txService;

    @Mock
    private KafkaTripEventPublisher kafkaPublisher;

    @Mock
    private RealtimeServiceClient realtimeServiceClient;

    private ObjectMapper objectMapper;
    private TripOutboxPublisher publisher;

    private final UUID eventId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(txService.markSent(any(), any(), any())).thenReturn(true);
        publisher = new TripOutboxPublisher(
                outboxRepository,
                txService,
                kafkaPublisher,
                realtimeServiceClient,
                objectMapper,
                true,
                "kafka",
                30000
        );
    }

    private TripOutboxEvent createTestEvent(String eventType) throws Exception {
        TripStatusPayload payload = new TripStatusPayload(
                tripId,
                customerId,
                driverId,
                "COMPLETED",
                "DRIVER",
                "Arrived at destination",
                now,
                1L
        );
        String json = objectMapper.writeValueAsString(payload);

        return new TripOutboxEvent(
                eventId,
                tripId,
                eventType,
                "TRIP",
                tripId,
                customerId,
                "CUSTOMER",
                "corr-trip-" + tripId,
                json,
                now
        );
    }

    @Test
    @DisplayName("Should successfully publish trip status to Kafka and mark SENT via txService")
    void shouldPublishTripEventViaKafkaSuccessfully() throws Exception {
        TripOutboxEvent event = createTestEvent("TRIP_COMPLETED");
        when(outboxRepository.findPendingEvents(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(txService.claimEvent(eq(event.getId()), any(Instant.class), any(Instant.class)))
                .thenReturn(Optional.of(event));

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("rhl.trip.lifecycle.v1", 0), 0, 0, 0, 0, 0
        );
        when(kafkaPublisher.publishTripEvent(eq(tripId), any())).thenReturn(metadata);

        int processed = publisher.processPendingEvents();

        assertThat(processed).isEqualTo(1);
        verify(txService).claimEvent(eq(event.getId()), any(Instant.class), any(Instant.class));
        verify(kafkaPublisher).publishTripEvent(eq(tripId), any());
        verify(txService).markSent(eq(event.getId()), any(Instant.class), any(Instant.class));
        verifyNoInteractions(realtimeServiceClient);
    }

    @Test
    @DisplayName("Should publish via legacy HTTP and revoke room when transport is legacy-http")
    void shouldPublishTripCompletedAndRevokeRoomViaLegacyHttp() throws Exception {
        TripOutboxPublisher httpPublisher = new TripOutboxPublisher(
                outboxRepository,
                txService,
                kafkaPublisher,
                realtimeServiceClient,
                objectMapper,
                true,
                "legacy-http",
                30000
        );

        TripOutboxEvent event = createTestEvent("TRIP_COMPLETED");
        when(outboxRepository.findPendingEvents(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(txService.claimEvent(eq(event.getId()), any(Instant.class), any(Instant.class)))
                .thenReturn(Optional.of(event));

        int processed = httpPublisher.processPendingEvents();

        assertThat(processed).isEqualTo(1);
        verify(realtimeServiceClient).publishTripStatus(eq(customerId), any());
        verify(realtimeServiceClient).revokeTripRoom(eq(tripId));
        verify(txService).markSent(eq(event.getId()), any(Instant.class), any(Instant.class));
        verifyNoInteractions(kafkaPublisher);
    }

    @Test
    @DisplayName("Should handle Kafka failure and record markFailed with backoff via txService")
    void shouldHandleFailureWithExponentialBackoff() throws Exception {
        TripOutboxEvent event = createTestEvent("TRIP_STATUS_CHANGED");
        when(outboxRepository.findPendingEvents(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(txService.claimEvent(eq(event.getId()), any(Instant.class), any(Instant.class)))
                .thenReturn(Optional.of(event));

        doThrow(new RuntimeException("Kafka broker down"))
                .when(kafkaPublisher).publishTripEvent(eq(tripId), any());

        int processed = publisher.processPendingEvents();

        assertThat(processed).isEqualTo(0);
        verify(txService, never()).markSent(any(), any(), any());
        verify(txService).markFailed(eq(event.getId()), any(Instant.class), contains("Kafka broker down"), any(Instant.class));
    }

    @Test
    @DisplayName("Should skip processing when publisher is disabled")
    void shouldSkipWhenDisabled() {
        TripOutboxPublisher disabledPublisher = new TripOutboxPublisher(
                outboxRepository,
                txService,
                kafkaPublisher,
                realtimeServiceClient,
                objectMapper,
                false,
                "kafka",
                30000
        );

        disabledPublisher.scheduledProcess();

        verifyNoInteractions(outboxRepository);
        verifyNoInteractions(txService);
        verifyNoInteractions(kafkaPublisher);
        verifyNoInteractions(realtimeServiceClient);
    }
}
