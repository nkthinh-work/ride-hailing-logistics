package com.rhl.realtime.consumers;

import com.rhl.realtime.models.DriverOfferPayload;
import com.rhl.realtime.models.EventEnvelope;
import com.rhl.realtime.models.RealtimeEventType;
import com.rhl.realtime.services.RealtimeEventPublisher;
import com.rhl.realtime.services.RealtimeSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kafka Consumer for Dispatch Domain Events (rhl.dispatch.offer.v1).
 * Ingests offer events, performs deduplication, validates offer TTL (drops expired offers),
 * and dispatches to driver's private STOMP user queue (/user/{driverId}/queue/offers).
 */
@Component
public class DispatchOfferKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(DispatchOfferKafkaListener.class);

    private final RealtimeEventPublisher realtimeEventPublisher;
    private final RealtimeSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    private final Map<UUID, Instant> processedEvents = new ConcurrentHashMap<>();

    public DispatchOfferKafkaListener(
            RealtimeEventPublisher realtimeEventPublisher,
            RealtimeSessionRegistry sessionRegistry,
            ObjectMapper objectMapper
    ) {
        this.realtimeEventPublisher = Objects.requireNonNull(realtimeEventPublisher, "realtimeEventPublisher is required");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @KafkaListener(
            topics = "${realtime.kafka.topic.dispatch-offer:rhl.dispatch.offer.v1}",
            groupId = "${realtime.kafka.group.dispatch:rhl.realtime.dispatch.v1}"
    )
    public void consumeDispatchOffer(String message) {
        if (message == null || message.isBlank()) {
            log.warn("Received empty dispatch offer Kafka message, skipping");
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(message);
            if (!root.has("eventId") || !root.has("payload")) {
                throw new IllegalArgumentException("Malformed dispatch offer event: missing eventId or payload");
            }

            UUID eventId = UUID.fromString(root.get("eventId").asText());
            String eventTypeStr = root.has("eventType") ? root.get("eventType").asText() : "DRIVER_OFFER_CREATED";
            String correlationId = root.has("correlationId") ? root.get("correlationId").asText() : null;

            // 1. Deduplication check (read-only before processing)
            if (isAlreadyProcessed(eventId)) {
                log.info("Duplicate dispatch offer event {} detected, skipping STOMP delivery", eventId);
                return;
            }

            JsonNode payloadNode = root.get("payload");
            DriverOfferPayload offerPayload = parseOfferPayload(payloadNode);

            // 2. Offer TTL / Expiry validation
            Instant now = Instant.now();
            if (offerPayload.expiresAt() != null && offerPayload.expiresAt().isBefore(now)) {
                log.warn("Offer {} for driver {} has already expired at {} (now: {}). Dropping STOMP broadcast.",
                        offerPayload.offerId(), offerPayload.driverId(), offerPayload.expiresAt(), now);
                markProcessed(eventId);
                return;
            }

            // 3. Dispatch to Driver private STOMP queue
            EventEnvelope<DriverOfferPayload> envelope = new EventEnvelope<>(
                    eventId,
                    RealtimeEventType.DRIVER_OFFER_CREATED,
                    1,
                    now,
                    correlationId,
                    offerPayload.offerId(),
                    offerPayload
            );

            realtimeEventPublisher.sendOfferToDriver(offerPayload.driverId(), envelope);
            // 4. Record processing outcome ONLY after successful STOMP delivery
            markProcessed(eventId);

            boolean isOnline = sessionRegistry.isUserConnected(offerPayload.driverId());
            log.info("Delivered dispatch offer {} for trip {} to driver {} via STOMP (driver online: {})",
                    offerPayload.offerId(), offerPayload.tripId(), offerPayload.driverId(), isOnline);

        } catch (IllegalArgumentException ex) {
            log.error("Fatal payload validation error in dispatch offer event: {}", ex.getMessage());
            throw ex; // Re-throw fatal exception to route directly to DLT via DefaultErrorHandler
        } catch (Exception ex) {
            log.error("Failed to process dispatch offer Kafka event: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error processing dispatch offer event", ex);
        }
    }

    private DriverOfferPayload parseOfferPayload(JsonNode node) {
        UUID offerId = node.has("offerId") ? UUID.fromString(node.get("offerId").asText()) : null;
        UUID tripId = node.has("tripId") ? UUID.fromString(node.get("tripId").asText()) : null;
        UUID driverId = node.has("driverId") ? UUID.fromString(node.get("driverId").asText()) : null;
        String serviceType = node.has("serviceType") ? node.get("serviceType").asText() : "RIDE";
        double pickupLatitude = node.has("pickupLatitude") ? node.get("pickupLatitude").asDouble() : 0.0;
        double pickupLongitude = node.has("pickupLongitude") ? node.get("pickupLongitude").asDouble() : 0.0;
        String pickupAddress = node.has("pickupAddress") ? node.get("pickupAddress").asText() : "";
        String dropoffAddress = node.has("dropoffAddress") ? node.get("dropoffAddress").asText() : "";
        Double estimatedDistanceKm = node.has("estimatedDistanceKm") && !node.get("estimatedDistanceKm").isNull()
                ? node.get("estimatedDistanceKm").asDouble() : null;
        BigDecimal estimatedFare = node.has("estimatedFare") && !node.get("estimatedFare").isNull()
                ? new BigDecimal(node.get("estimatedFare").asText()) : BigDecimal.ZERO;
        Instant offeredAt = node.has("offeredAt") && !node.get("offeredAt").isNull()
                ? Instant.parse(node.get("offeredAt").asText()) : Instant.now();
        Instant expiresAt = node.has("expiresAt") && !node.get("expiresAt").isNull()
                ? Instant.parse(node.get("expiresAt").asText()) : null;
        String status = node.has("status") ? node.get("status").asText() : "CREATED";

        if (driverId == null || tripId == null) {
            throw new IllegalArgumentException("DriverOfferPayload requires non-null driverId and tripId");
        }

        return new DriverOfferPayload(
                offerId,
                tripId,
                driverId,
                serviceType,
                pickupLatitude,
                pickupLongitude,
                pickupAddress,
                dropoffAddress,
                estimatedDistanceKm,
                estimatedFare,
                offeredAt,
                expiresAt,
                status
        );
    }

    public boolean isAlreadyProcessed(UUID eventId) {
        return eventId != null && processedEvents.containsKey(eventId);
    }

    public void markProcessed(UUID eventId) {
        if (eventId == null) return;
        Instant now = Instant.now();
        if (processedEvents.size() > 5000) {
            Instant cutoff = now.minusSeconds(600);
            processedEvents.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }
        processedEvents.put(eventId, now);
    }

    public int getProcessedEventsCount() {
        return processedEvents.size();
    }
}
