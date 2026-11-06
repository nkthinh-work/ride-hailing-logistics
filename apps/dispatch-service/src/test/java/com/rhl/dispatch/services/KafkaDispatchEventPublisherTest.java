package com.rhl.dispatch.services;

import com.rhl.dispatch.events.DispatchEventEnvelope;
import com.rhl.dispatch.events.DispatchOfferPayload;
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

import java.math.BigDecimal;
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
@DisplayName("KafkaDispatchEventPublisher Unit Tests")
class KafkaDispatchEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private KafkaDispatchEventPublisher publisher;

    private final UUID tripId = UUID.randomUUID();
    private final UUID offerId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        publisher = new KafkaDispatchEventPublisher(
                kafkaTemplate,
                objectMapper,
                "rhl.dispatch.offer.v1",
                3000
        );
    }

    private DispatchEventEnvelope<DispatchOfferPayload> createEnvelope() {
        DispatchOfferPayload payload = new DispatchOfferPayload(
                offerId,
                tripId,
                driverId,
                "RIDE",
                10.5,
                106.6,
                "Pickup",
                "Dropoff",
                5.0,
                BigDecimal.valueOf(50000),
                now,
                now.plusSeconds(15),
                "CREATED"
        );
        return new DispatchEventEnvelope<>(
                UUID.randomUUID(),
                "DRIVER_OFFER_CREATED",
                1,
                now,
                "corr-1",
                offerId,
                payload
        );
    }

    @Test
    @DisplayName("publishOfferEvent should send message with tripId key and return RecordMetadata on success")
    void publishOfferEvent_shouldSendWithTripIdKey_andReturnMetadata() {
        DispatchEventEnvelope<DispatchOfferPayload> envelope = createEnvelope();

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("rhl.dispatch.offer.v1", 0), 0, 5, 0, 0, 0
        );
        SendResult<String, String> sendResult = new SendResult<>(null, metadata);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(eq("rhl.dispatch.offer.v1"), eq(tripId.toString()), anyString()))
                .thenReturn(future);

        RecordMetadata result = publisher.publishOfferEvent(tripId, envelope);

        assertThat(result).isNotNull();
        assertThat(result.topic()).isEqualTo("rhl.dispatch.offer.v1");
        assertThat(result.offset()).isEqualTo(5);
        verify(kafkaTemplate).send(eq("rhl.dispatch.offer.v1"), eq(tripId.toString()), anyString());
    }

    @Test
    @DisplayName("publishOfferEvent should throw exception when Kafka broker fails")
    void publishOfferEvent_shouldThrowWhenKafkaBrokerFails() {
        DispatchEventEnvelope<DispatchOfferPayload> envelope = createEnvelope();

        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Broker connection timeout"));

        when(kafkaTemplate.send(eq("rhl.dispatch.offer.v1"), eq(tripId.toString()), anyString()))
                .thenReturn(future);

        assertThatThrownBy(() -> publisher.publishOfferEvent(tripId, envelope))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Kafka publish failed");
    }
}
