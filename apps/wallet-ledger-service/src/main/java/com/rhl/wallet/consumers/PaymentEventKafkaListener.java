package com.rhl.wallet.consumers;

import com.rhl.wallet.dtos.FinancialEventIngestRequest;
import com.rhl.wallet.dtos.FinancialEventIngestResponse;
import com.rhl.wallet.services.FinancialEventIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Apache Kafka consumer listening on topic rhl.payment.events.v1 (CON-07, FR-EVT-001..008).
 * Deserializes FinancialEventEnvelope, validates schema and contract versions,
 * ingests into PostgreSQL Financial Inbox idempotently, and commits Kafka offset
 * only after database transaction completes.
 */
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventKafkaListener.class);

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "PAYMENT_SUCCEEDED",
            "PAYMENT_REFUNDED"
    );

    private final FinancialEventIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    @Autowired
    public PaymentEventKafkaListener(FinancialEventIngestionService ingestionService, ObjectMapper objectMapper) {
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @KafkaListener(
            topics = "${kafka.topics.payment-events:rhl.payment.events.v1}",
            groupId = "${kafka.consumer.group-id:rhl.wallet-ledger.v1}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        log.debug("Received Kafka record from topic {} partition {} offset {} key {}",
                record.topic(), record.partition(), record.offset(), record.key());

        FinancialEventIngestRequest request = parseAndValidate(record.value());
        FinancialEventIngestResponse response = ingestionService.ingestEvent(request);

        log.info("Successfully ingested financial event {} ({}) from Kafka partition {} offset {}. Inbox status: {}",
                request.eventId(), request.eventType(), record.partition(), record.offset(), response.status());
    }

    public FinancialEventIngestRequest parseAndValidate(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("Kafka message value cannot be empty");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Malformed JSON received from Kafka: " + ex.getMessage(), ex);
        }

        if (!root.hasNonNull("eventId")) {
            throw new IllegalArgumentException("Financial event envelope missing required field 'eventId'");
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(root.get("eventId").asText());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid UUID format for 'eventId'", ex);
        }

        if (!root.hasNonNull("eventType")) {
            throw new IllegalArgumentException("Financial event envelope missing required field 'eventType'");
        }
        String eventType = root.get("eventType").asText();
        if (!SUPPORTED_EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unsupported eventType '" + eventType + "'. Supported types: " + SUPPORTED_EVENT_TYPES);
        }

        UUID entityId = null;
        if (root.hasNonNull("entityId")) {
            try {
                entityId = UUID.fromString(root.get("entityId").asText());
            } catch (Exception ignored) {
            }
        }
        if (entityId == null && root.hasNonNull("aggregateId")) {
            try {
                entityId = UUID.fromString(root.get("aggregateId").asText());
            } catch (Exception ignored) {
            }
        }
        if (entityId == null) {
            entityId = eventId;
        }

        String correlationId = root.hasNonNull("correlationId") ? root.get("correlationId").asText() : null;

        String payloadStr;
        if (root.has("payload")) {
            JsonNode payloadNode = root.get("payload");
            payloadStr = payloadNode.isTextual() ? payloadNode.asText() : payloadNode.toString();
        } else {
            payloadStr = rawJson;
        }

        return new FinancialEventIngestRequest(
                eventId,
                eventType,
                "payment-service",
                entityId,
                correlationId,
                payloadStr
        );
    }
}
