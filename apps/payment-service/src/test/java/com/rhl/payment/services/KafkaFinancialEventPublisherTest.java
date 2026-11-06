package com.rhl.payment.services;

import com.rhl.payment.events.FinancialEventEnvelope;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaFinancialEventPublisher Unit Tests — Broker ACK Verification")
class KafkaFinancialEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaFinancialEventPublisher publisher;
    private final String topic = "rhl.payment.events.v1";

    @BeforeEach
    void setUp() {
        publisher = new KafkaFinancialEventPublisher(kafkaTemplate, objectMapper, topic, 2000);
    }

    @Test
    @DisplayName("Publishes event to Kafka topic with paymentId key and succeeds on broker ACK")
    void onEvent_success_verifiedBrokerAck() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        FinancialEventEnvelope<Map<String, Object>> envelope = new FinancialEventEnvelope<>(
                eventId,
                "PAYMENT_SUCCEEDED",
                1,
                Instant.now(),
                "corr-100",
                paymentId,
                Map.of("amount", 60000, "currency", "VND")
        );

        RecordMetadata metadata = new RecordMetadata(new TopicPartition(topic, 0), 0, 0, 0, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(null, metadata);
        when(kafkaTemplate.send(eq(topic), eq(paymentId.toString()), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.onEvent(envelope);

        verify(kafkaTemplate).send(eq(topic), eq(paymentId.toString()), anyString());
    }

    @Test
    @DisplayName("Throws RuntimeException when Kafka broker send fails or times out")
    void onEvent_brokerFails_throwsException() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        FinancialEventEnvelope<Map<String, Object>> envelope = new FinancialEventEnvelope<>(
                eventId,
                "PAYMENT_SUCCEEDED",
                1,
                Instant.now(),
                "corr-100",
                paymentId,
                Map.of("amount", 60000)
        );

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker unreachable"));
        when(kafkaTemplate.send(eq(topic), eq(paymentId.toString()), anyString()))
                .thenReturn(failedFuture);

        assertThatThrownBy(() -> publisher.onEvent(envelope))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Kafka publish failed for event");
    }
}
