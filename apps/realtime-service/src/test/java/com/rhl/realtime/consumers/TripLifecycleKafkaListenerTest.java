package com.rhl.realtime.consumers;

import com.rhl.realtime.models.EventEnvelope;
import com.rhl.realtime.models.TripStatusPayload;
import com.rhl.realtime.services.RealtimeEventPublisher;
import com.rhl.realtime.services.TripMembershipProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TripLifecycleKafkaListener Unit Tests")
class TripLifecycleKafkaListenerTest {

    @Mock
    private RealtimeEventPublisher realtimeEventPublisher;

    @Mock
    private TripMembershipProvider membershipProvider;

    private ObjectMapper objectMapper;
    private TripLifecycleKafkaListener listener;

    private final UUID eventId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new TripLifecycleKafkaListener(
                realtimeEventPublisher,
                membershipProvider,
                objectMapper
        );
    }

    private String buildTripMessage(UUID evtId, String status, Long sequence) throws Exception {
        TripStatusPayload payload = new TripStatusPayload(
                tripId,
                customerId,
                driverId,
                status,
                "DRIVER",
                "Status update: " + status,
                now,
                sequence
        );
        EventEnvelope<TripStatusPayload> envelope = new EventEnvelope<>(
                evtId,
                null,
                1,
                now,
                "corr-1",
                tripId,
                payload
        );
        return objectMapper.writeValueAsString(envelope);
    }

    @Test
    @DisplayName("Should successfully consume trip update and deliver to customer via STOMP")
    void shouldConsumeTripUpdateAndDeliverToCustomer() throws Exception {
        String json = buildTripMessage(eventId, "ACCEPTED", 1L);

        listener.consumeTripLifecycle(json);

        ArgumentCaptor<EventEnvelope<TripStatusPayload>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(realtimeEventPublisher).sendTripUpdateToCustomer(eq(customerId), captor.capture());

        EventEnvelope<TripStatusPayload> captured = captor.getValue();
        assertThat(captured.eventId()).isEqualTo(eventId);
        assertThat(captured.payload().status()).isEqualTo("ACCEPTED");
        assertThat(captured.payload().tripId()).isEqualTo(tripId);
        verify(membershipProvider, never()).invalidateTrip(any());
    }

    @Test
    @DisplayName("P0 Security: Should revoke trip room authorization upon TRIP_COMPLETED")
    void shouldRevokeRoomUponTripCompleted() throws Exception {
        String json = buildTripMessage(eventId, "COMPLETED", 5L);

        listener.consumeTripLifecycle(json);

        verify(realtimeEventPublisher).sendTripUpdateToCustomer(eq(customerId), any());
        verify(membershipProvider, times(1)).invalidateTrip(eq(tripId));
        assertThat(listener.isTripTerminal(tripId)).isTrue();
    }

    @Test
    @DisplayName("P0 Security: Should revoke trip room authorization upon TRIP_CANCELLED")
    void shouldRevokeRoomUponTripCancelled() throws Exception {
        String json = buildTripMessage(eventId, "CANCELLED", 2L);

        listener.consumeTripLifecycle(json);

        verify(realtimeEventPublisher).sendTripUpdateToCustomer(eq(customerId), any());
        verify(membershipProvider, times(1)).invalidateTrip(eq(tripId));
        assertThat(listener.isTripTerminal(tripId)).isTrue();
    }

    @Test
    @DisplayName("Should drop late non-terminal event arriving after trip is already terminal (stale event)")
    void shouldDropStaleEventAfterTerminal() throws Exception {
        // Step 1: Terminal event arrives
        String completedJson = buildTripMessage(eventId, "COMPLETED", 5L);
        listener.consumeTripLifecycle(completedJson);
        verify(realtimeEventPublisher, times(1)).sendTripUpdateToCustomer(eq(customerId), any());

        // Step 2: Late ARRIVED event arrives from Kafka redelivery/delay
        UUID lateEventId = UUID.randomUUID();
        String lateJson = buildTripMessage(lateEventId, "ARRIVED", 3L);
        listener.consumeTripLifecycle(lateJson);

        // STOMP send should still be 1 (late event was dropped)
        verify(realtimeEventPublisher, times(1)).sendTripUpdateToCustomer(eq(customerId), any());
    }

    @Test
    @DisplayName("Should drop out-of-order event with sequence lower than latest seen")
    void shouldDropOutOfOrderEvent() throws Exception {
        // Seq 3 arrives
        String seq3 = buildTripMessage(UUID.randomUUID(), "IN_TRIP", 3L);
        listener.consumeTripLifecycle(seq3);
        verify(realtimeEventPublisher, times(1)).sendTripUpdateToCustomer(eq(customerId), any());

        // Seq 2 arrives late (out of order)
        String seq2 = buildTripMessage(UUID.randomUUID(), "ARRIVED", 2L);
        listener.consumeTripLifecycle(seq2);

        // STOMP send should still be 1
        verify(realtimeEventPublisher, times(1)).sendTripUpdateToCustomer(eq(customerId), any());
    }

    @Test
    @DisplayName("Should deduplicate repeated messages with same eventId")
    void shouldDeduplicateRepeatedMessages() throws Exception {
        String json = buildTripMessage(eventId, "PICKING_UP", 2L);

        listener.consumeTripLifecycle(json);
        listener.consumeTripLifecycle(json);

        verify(realtimeEventPublisher, times(1)).sendTripUpdateToCustomer(eq(customerId), any());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException on malformed payload to route to DLT")
    void shouldThrowOnMalformedPayload() {
        String malformed = "{\"bad\":\"data\"}";

        assertThatThrownBy(() -> listener.consumeTripLifecycle(malformed))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(realtimeEventPublisher);
    }

    @Test
    @DisplayName("P0 Reliability: Should not mark event processed if STOMP publish throws, allowing Kafka retry")
    void shouldNotMarkProcessedWhenPublishThrowsException() throws Exception {
        String json = buildTripMessage(eventId, "PICKING_UP", 2L);

        // Attempt 1: Transient STOMP failure
        doThrow(new RuntimeException("Transient STOMP socket error"))
                .doNothing()
                .when(realtimeEventPublisher).sendTripUpdateToCustomer(eq(customerId), any());

        assertThatThrownBy(() -> listener.consumeTripLifecycle(json))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error processing trip lifecycle event");

        assertThat(listener.isAlreadyProcessed(eventId)).isFalse();

        // Attempt 2: Kafka retry successfully delivers
        listener.consumeTripLifecycle(json);

        assertThat(listener.isAlreadyProcessed(eventId)).isTrue();
        verify(realtimeEventPublisher, times(2)).sendTripUpdateToCustomer(eq(customerId), any());
    }
}
