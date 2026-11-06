package com.rhl.payment.services;

import com.rhl.payment.events.FinancialEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Real Apache Kafka event publisher for financial outbox events (CON-07, FR-EVT-001..008).
 * Enforces partition ordering by paymentId, synchronous broker ACK verification (acks=all),
 * bounded timeout, and refuses to mark outbox rows SENT without broker acknowledgment.
 */
@Service
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaFinancialEventPublisher implements FinancialEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaFinancialEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final long timeoutMs;

    @Autowired
    public KafkaFinancialEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${kafka.topics.payment-events:rhl.payment.events.v1}") String topic,
            @Value("${kafka.producer.timeout-ms:5000}") long timeoutMs
    ) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.topic = topic;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void onEvent(FinancialEventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope is required");
        String partitionKey = envelope.entityId() != null ? envelope.entityId().toString() : envelope.eventId().toString();
        try {
            String jsonPayload = objectMapper.writeValueAsString(envelope);
            log.debug("Publishing financial event {} to topic {} with key {}", envelope.eventId(), topic, partitionKey);

            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, partitionKey, jsonPayload);
            SendResult<String, String> sendResult = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            var metadata = sendResult.getRecordMetadata();
            log.info("Kafka broker ACK verified: eventId={}, topic={}, partition={}, offset={}",
                    envelope.eventId(), metadata.topic(), metadata.partition(), metadata.offset());
        } catch (Exception ex) {
            log.error("Failed to publish financial event {} to Kafka topic {}: {}", envelope.eventId(), topic, ex.getMessage());
            throw new RuntimeException("Kafka publish failed for event " + envelope.eventId(), ex);
        }
    }
}
