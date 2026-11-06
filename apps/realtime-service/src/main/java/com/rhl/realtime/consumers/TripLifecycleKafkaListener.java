package com.rhl.realtime.consumers;

import com.rhl.realtime.models.EventEnvelope;
import com.rhl.realtime.models.RealtimeEventType;
import com.rhl.realtime.models.TripStatusPayload;
import com.rhl.realtime.services.RealtimeEventPublisher;
import com.rhl.realtime.services.TripMembershipProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kafka Consumer for Trip Lifecycle Events (rhl.trip.lifecycle.v1).
 * Features:
 * 1. Event deduplication via eventId.
 * 2. Out-of-order & Stale event protection using monotonic sequence numbers and terminal status guards.
 * 3. Authoritative P0 Terminal Room Revocation: Invalidates STOMP trip room access upon COMPLETED/CANCELLED.
 * 4. Dispatches live status updates to customer private queue (/user/{customerId}/queue/trips).
 */
@Component
public class TripLifecycleKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(TripLifecycleKafkaListener.class);

    private final RealtimeEventPublisher realtimeEventPublisher;
    private final TripMembershipProvider membershipProvider;
    private final ObjectMapper objectMapper;

    private final Map<UUID, Instant> processedEvents = new ConcurrentHashMap<>();
    private final Map<UUID, Long> tripLastSequenceMap = new ConcurrentHashMap<>();
    private final Set<UUID> terminalTrips = ConcurrentHashMap.newKeySet();

    public TripLifecycleKafkaListener(
            RealtimeEventPublisher realtimeEventPublisher,
            TripMembershipProvider membershipProvider,
            ObjectMapper objectMapper
    ) {
        this.realtimeEventPublisher = Objects.requireNonNull(realtimeEventPublisher, "realtimeEventPublisher is required");
        this.membershipProvider = Objects.requireNonNull(membershipProvider, "membershipProvider is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @KafkaListener(
            topics = "${realtime.kafka.topic.trip-lifecycle:rhl.trip.lifecycle.v1}",
            groupId = "${realtime.kafka.group.trip:rhl.realtime.trip.v1}"
    )
    public void consumeTripLifecycle(String message) {
        if (message == null || message.isBlank()) {
            log.warn("Received empty trip lifecycle Kafka message, skipping");
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(message);
            if (!root.has("eventId") || !root.has("payload")) {
                throw new IllegalArgumentException("Malformed trip lifecycle event: missing eventId or payload");
            }

            UUID eventId = UUID.fromString(root.get("eventId").asText());
            String eventTypeStr = root.has("eventType") ? root.get("eventType").asText() : "TRIP_STATUS_CHANGED";
            String correlationId = root.has("correlationId") ? root.get("correlationId").asText() : null;

            // 1. Deduplication check (read-only before processing)
            if (isAlreadyProcessed(eventId)) {
                log.info("Duplicate trip lifecycle event {} detected, skipping STOMP delivery", eventId);
                return;
            }

            JsonNode payloadNode = root.get("payload");
            TripStatusPayload payload = parseTripStatusPayload(payloadNode);

            UUID tripId = payload.tripId();
            String status = payload.status();
            boolean isTerminal = isTerminalStatus(status);

            // 2. Out-of-order & Stale event protection (Section 8)
            if (terminalTrips.contains(tripId) && !isTerminal) {
                log.warn("Stale event detected: trip {} is already terminal, dropping late non-terminal event with status {}",
                        tripId, status);
                markProcessed(eventId);
                return;
            }

            if (payload.sequence() != null) {
                Long lastSeq = tripLastSequenceMap.get(tripId);
                if (lastSeq != null && payload.sequence() < lastSeq) {
                    log.warn("Out-of-order event detected for trip {}: current seq {} < lastSeq {}, dropping update",
                            tripId, payload.sequence(), lastSeq);
                    markProcessed(eventId);
                    return;
                }
                tripLastSequenceMap.put(tripId, payload.sequence());
            }

            // 3. P0 Terminal Room Revocation (Section 9)
            if (isTerminal) {
                terminalTrips.add(tripId);
                membershipProvider.invalidateTrip(tripId);
                log.info("P0 Security: Revoked trip room access for terminal trip {} (status: {})", tripId, status);
            }

            // 4. Dispatch to Customer private STOMP queue
            RealtimeEventType resolvedType = resolveEventType(eventTypeStr, status);
            EventEnvelope<TripStatusPayload> envelope = new EventEnvelope<>(
                    eventId,
                    resolvedType,
                    1,
                    payload.occurredAt() != null ? payload.occurredAt() : Instant.now(),
                    correlationId,
                    tripId,
                    payload
            );

            realtimeEventPublisher.sendTripUpdateToCustomer(payload.customerId(), envelope);
            // 5. Record processing outcome ONLY after successful STOMP delivery
            markProcessed(eventId);

            log.info("Delivered trip status {} for trip {} to customer {} via STOMP",
                    status, tripId, payload.customerId());

        } catch (IllegalArgumentException ex) {
            log.error("Fatal payload validation error in trip lifecycle event: {}", ex.getMessage());
            throw ex; // Routed to DLT via DefaultErrorHandler
        } catch (Exception ex) {
            log.error("Failed to process trip lifecycle Kafka event: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error processing trip lifecycle event", ex);
        }
    }

    private RealtimeEventType resolveEventType(String eventTypeStr, String status) {
        if ("TRIP_COMPLETED".equalsIgnoreCase(eventTypeStr) || "COMPLETED".equalsIgnoreCase(status)) {
            return RealtimeEventType.TRIP_COMPLETED;
        }
        if ("TRIP_CANCELLED".equalsIgnoreCase(eventTypeStr) || "CANCELLED".equalsIgnoreCase(status)) {
            return RealtimeEventType.TRIP_CANCELLED;
        }
        if ("TRIP_DRIVER_ASSIGNED".equalsIgnoreCase(eventTypeStr) || "ACCEPTED".equalsIgnoreCase(status)) {
            return RealtimeEventType.TRIP_DRIVER_ASSIGNED;
        }
        return RealtimeEventType.TRIP_STATUS_CHANGED;
    }

    private TripStatusPayload parseTripStatusPayload(JsonNode node) {
        UUID tripId = node.has("tripId") ? UUID.fromString(node.get("tripId").asText()) : null;
        UUID customerId = node.has("customerId") ? UUID.fromString(node.get("customerId").asText()) : null;
        UUID driverId = node.has("driverId") && !node.get("driverId").isNull()
                ? UUID.fromString(node.get("driverId").asText()) : null;
        String status = node.has("status") ? node.get("status").asText() : "UNKNOWN";
        String actor = node.has("actor") ? node.get("actor").asText() : "SYSTEM";
        String reason = node.has("reason") ? node.get("reason").asText() : null;
        Instant occurredAt = node.has("occurredAt") && !node.get("occurredAt").isNull()
                ? Instant.parse(node.get("occurredAt").asText()) : Instant.now();
        Long sequence = node.has("sequence") && !node.get("sequence").isNull()
                ? node.get("sequence").asLong() : null;

        if (tripId == null || customerId == null) {
            throw new IllegalArgumentException("TripStatusPayload requires non-null tripId and customerId");
        }

        return new TripStatusPayload(
                tripId,
                customerId,
                driverId,
                status,
                actor,
                reason,
                occurredAt,
                sequence
        );
    }

    private boolean isTerminalStatus(String status) {
        if (status == null) return false;
        String normalized = status.toUpperCase().trim();
        return "COMPLETED".equals(normalized)
                || "CANCELLED".equals(normalized)
                || "NO_DRIVER".equals(normalized)
                || "TRIP_COMPLETED".equals(normalized)
                || "TRIP_CANCELLED".equals(normalized);
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

    public boolean isTripTerminal(UUID tripId) {
        return terminalTrips.contains(tripId);
    }
}
