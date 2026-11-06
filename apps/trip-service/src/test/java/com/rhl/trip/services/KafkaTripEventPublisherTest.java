package com.rhl.trip.services;

import com.rhl.trip.events.TripEventEnvelope;
import com.rhl.trip.events.TripStatusPayload;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaTripEventPublisher Unit Tests")
class KafkaTripEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private KafkaTripEventPublisher publisher;

    private final UUID tripId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        publisher = new KafkaTripEventPublisher(
                kafkaTemplate,
                objectMapper,
                "rhl.trip.lifecycle.v1",
                3000
        );
    }

    private TripEventEnvelope<TripStatusPayload> createEnvelope() {
        TripStatusPayload payload = new TripStatusPayload(
                tripId,
                customerId,
                driverId,
                "IN_TRIP",
                "DRIVER",
                "Trip started",
                now,
                3L
        );
        return new TripEventEnvelope<>(
                UUID.randomUUID(),
                "TRIP_STATUS_CHANGED",
                1,
                now,
                "corr-1",
                tripId,
                payload
        );
    }

    @Test
    @DisplayName("publishTripEvent should send message with tripId key and return RecordMetadata on success")
    void publishTripEvent_shouldSendWithTripIdKey_andReturnMetadata() {
        TripEventEnvelope<TripStatusPayload> envelope = createEnvelope();

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("rhl.trip.lifecycle.v1", 0), 0, 7, 0, 0, 0
        );
        SendResult<String, String> sendResult = new SendResult<>(null, metadata);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(eq("rhl.trip.lifecycle.v1"), eq(tripId.toString()), anyString()))
                .thenReturn(future);

        RecordMetadata result = publisher.publishTripEvent(tripId, envelope);

        assertThat(result).isNotNull();
        assertThat(result.topic()).isEqualTo("rhl.trip.lifecycle.v1");
        assertThat(result.offset()).isEqualTo(7);
        verify(kafkaTemplate).send(eq("rhl.trip.lifecycle.v1"), eq(tripId.toString()), anyString());
    }

    @Test
    @DisplayName("publishTripEvent should throw exception when Kafka broker fails")
    void publishTripEvent_shouldThrowWhenKafkaBrokerFails() {
        TripEventEnvelope<TripStatusPayload> envelope = createEnvelope();

        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Broker connection timeout"));

        when(kafkaTemplate.send(eq("rhl.trip.lifecycle.v1"), eq(tripId.toString()), anyString()))
                .thenReturn(future);

        assertThatThrownBy(() -> publisher.publishTripEvent(tripId, envelope))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Kafka publish failed");
    }
}
