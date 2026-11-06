package com.rhl.trip.services;

import com.rhl.trip.events.TripEventEnvelope;
import com.rhl.trip.events.TripStatusPayload;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class KafkaTripEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTripEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topicName;
    private final long timeoutMs;

    public KafkaTripEventPublisher(
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${trip.kafka.topic.trip-lifecycle:rhl.trip.lifecycle.v1}") String topicName,
            @Value("${trip.kafka.producer.timeout-ms:5000}") long timeoutMs
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.topicName = topicName;
        this.timeoutMs = timeoutMs;
    }

    public boolean isKafkaAvailable() {
        return kafkaTemplate != null;
    }

    public RecordMetadata publishTripEvent(UUID tripId, TripEventEnvelope<TripStatusPayload> envelope) {
        if (kafkaTemplate == null) {
            throw new IllegalStateException("KafkaTemplate is not configured or Kafka is disabled for trip-service");
        }
        Objects.requireNonNull(tripId, "tripId key is required");
        Objects.requireNonNull(envelope, "envelope is required");

        try {
            String jsonPayload = objectMapper.writeValueAsString(envelope);
            String partitionKey = tripId.toString();

            log.info("Sending trip lifecycle event {} for tripId {} (status: {}) to topic {}",
                    envelope.eventId(), partitionKey, envelope.payload().status(), topicName);

            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topicName, partitionKey, jsonPayload);
            SendResult<String, String> result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            RecordMetadata metadata = result.getRecordMetadata();

            log.info("Successfully published trip lifecycle event {} to Kafka: topic={}, partition={}, offset={}",
                    envelope.eventId(), metadata.topic(), metadata.partition(), metadata.offset());
            return metadata;
        } catch (Exception ex) {
            log.error("Failed to publish trip lifecycle event {} to Kafka: {}",
                    envelope.eventId(), ex.getMessage(), ex);
            throw new RuntimeException("Kafka publish failed for trip lifecycle event: " + envelope.eventId(), ex);
        }
    }
}
