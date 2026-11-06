package com.rhl.realtime.consumers;

import com.rhl.realtime.models.DriverOfferPayload;
import com.rhl.realtime.models.EventEnvelope;
import com.rhl.realtime.services.RealtimeEventPublisher;
import com.rhl.realtime.services.RealtimeSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DispatchOfferKafkaListener Unit Tests")
class DispatchOfferKafkaListenerTest {

    @Mock
    private RealtimeEventPublisher realtimeEventPublisher;

    @Mock
    private RealtimeSessionRegistry sessionRegistry;

    private ObjectMapper objectMapper;
    private DispatchOfferKafkaListener listener;

    private final UUID eventId = UUID.randomUUID();
    private final UUID offerId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new DispatchOfferKafkaListener(
                realtimeEventPublisher,
                sessionRegistry,
                objectMapper
        );
    }

    private String buildValidMessage(Instant expiresAt) throws Exception {
        DriverOfferPayload payload = new DriverOfferPayload(
                offerId,
                tripId,
                driverId,
                "RIDE",
                10.7769,
                106.7009,
                "123 Le Loi, D1",
                "456 Nguyen Hue, D1",
                2.5,
                BigDecimal.valueOf(45000),
                now,
                expiresAt,
                "CREATED"
        );
        EventEnvelope<DriverOfferPayload> envelope = new EventEnvelope<>(
                eventId,
                null,
                1,
                now,
                "corr-1",
                offerId,
                payload
        );
        return objectMapper.writeValueAsString(envelope);
    }

    @Test
    @DisplayName("Should successfully consume valid offer and dispatch to driver via STOMP")
    void shouldConsumeValidOfferAndDispatchToDriver() throws Exception {
        String json = buildValidMessage(Instant.now().plusSeconds(30));
        when(sessionRegistry.isUserConnected(driverId)).thenReturn(true);

        listener.consumeDispatchOffer(json);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventEnvelope<DriverOfferPayload>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(realtimeEventPublisher).sendOfferToDriver(eq(driverId), captor.capture());

        EventEnvelope<DriverOfferPayload> captured = captor.getValue();
        assertThat(captured.eventId()).isEqualTo(eventId);
        assertThat(captured.payload().offerId()).isEqualTo(offerId);
        assertThat(captured.payload().tripId()).isEqualTo(tripId);
    }

    @Test
    @DisplayName("Should drop expired offer and NOT broadcast to driver via STOMP")
    void shouldDropExpiredOffer_andNotBroadcastToDriver() throws Exception {
        // Offer expired 10 seconds ago
        String json = buildValidMessage(Instant.now().minusSeconds(10));

        listener.consumeDispatchOffer(json);

        verify(realtimeEventPublisher, never()).sendOfferToDriver(any(), any());
    }

    @Test
    @DisplayName("Should deduplicate repeated messages with same eventId")
    void shouldDeduplicateRepeatedMessages() throws Exception {
        String json = buildValidMessage(Instant.now().plusSeconds(30));
        when(sessionRegistry.isUserConnected(driverId)).thenReturn(true);

        // First delivery
        listener.consumeDispatchOffer(json);
        // Second delivery (duplicate redelivery)
        listener.consumeDispatchOffer(json);

        // Must only be sent once
        verify(realtimeEventPublisher, times(1)).sendOfferToDriver(eq(driverId), any());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException on malformed payload to route to DLT")
    void shouldThrowOnMalformedPayload() {
        String malformedJson = "{\"invalid\":\"schema\"}";

        assertThatThrownBy(() -> listener.consumeDispatchOffer(malformedJson))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(realtimeEventPublisher);
    }

    @Test
    @DisplayName("P0 Reliability: Should not mark event processed if STOMP publish throws, allowing Kafka retry")
    void shouldNotMarkProcessedWhenPublishThrowsException() throws Exception {
        String json = buildValidMessage(Instant.now().plusSeconds(30));

        // Attempt 1: Transient STOMP failure
        doThrow(new RuntimeException("Transient STOMP socket error"))
                .doNothing()
                .when(realtimeEventPublisher).sendOfferToDriver(eq(driverId), any());

        assertThatThrownBy(() -> listener.consumeDispatchOffer(json))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error processing dispatch offer event");

        assertThat(listener.isAlreadyProcessed(eventId)).isFalse();

        // Attempt 2: Kafka retry successfully delivers
        when(sessionRegistry.isUserConnected(driverId)).thenReturn(true);
        listener.consumeDispatchOffer(json);

        assertThat(listener.isAlreadyProcessed(eventId)).isTrue();
        verify(realtimeEventPublisher, times(2)).sendOfferToDriver(eq(driverId), any());
    }
}
