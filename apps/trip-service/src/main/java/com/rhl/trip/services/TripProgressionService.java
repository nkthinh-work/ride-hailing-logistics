package com.rhl.trip.services;

import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.TripAccessDeniedException;
import com.rhl.trip.exceptions.TripNotFoundException;
import com.rhl.trip.repositories.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service managing Driver Trip Lifecycle Progression (M05 Group 3, FR-TRIP-011..014).
 * Transitions: ACCEPTED -> PICKING_UP -> ARRIVED -> IN_TRIP -> COMPLETED.
 */
@Service
public class TripProgressionService {

    private static final Logger log = LoggerFactory.getLogger(TripProgressionService.class);

    private final TripRepository tripRepository;
    private com.rhl.trip.clients.RealtimeServiceClient realtimeServiceClient;
    private com.rhl.trip.repositories.TripOutboxRepository outboxRepository;
    private tools.jackson.databind.ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public TripProgressionService(
            TripRepository tripRepository,
            com.rhl.trip.repositories.TripOutboxRepository outboxRepository,
            tools.jackson.databind.ObjectMapper objectMapper
    ) {
        this.tripRepository = tripRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public TripProgressionService(TripRepository tripRepository) {
        this(tripRepository, null, new tools.jackson.databind.ObjectMapper());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRealtimeServiceClient(com.rhl.trip.clients.RealtimeServiceClient realtimeServiceClient) {
        this.realtimeServiceClient = realtimeServiceClient;
    }

    private void recordOutboxEvent(Trip trip, String eventType, String actor, String reason, Instant now) {
        if (outboxRepository == null) {
            return;
        }
        try {
            com.rhl.trip.events.TripStatusPayload payload = new com.rhl.trip.events.TripStatusPayload(
                    trip.getId(),
                    trip.getCustomerId(),
                    trip.getDriverId(),
                    trip.getStatus().name(),
                    actor,
                    reason,
                    now,
                    trip.getVersion()
            );

            String jsonPayload = objectMapper != null ? objectMapper.writeValueAsString(payload) : "{}";
            com.rhl.trip.entities.TripOutboxEvent outboxEvent = new com.rhl.trip.entities.TripOutboxEvent(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    eventType,
                    "TRIP",
                    trip.getId(),
                    trip.getCustomerId(),
                    "CUSTOMER",
                    "corr-trip-" + trip.getId(),
                    jsonPayload,
                    now
            );
            outboxRepository.save(outboxEvent);
            log.info("Enqueued trip outbox event {} ({}) for trip {} to customer {}",
                    outboxEvent.getEventId(), eventType, trip.getId(), trip.getCustomerId());
        } catch (Exception ex) {
            log.error("Failed to record trip outbox event for trip {}: {}", trip.getId(), ex.getMessage(), ex);
            throw new RuntimeException("Error persisting trip outbox event", ex);
        }
    }

    /**
     * Driver starts heading towards pickup location (ACCEPTED -> PICKING_UP).
     */
    @Transactional
    public TripResponse startPickingUp(UUID tripId, UUID driverId) {
        Objects.requireNonNull(tripId, "tripId is required");
        Objects.requireNonNull(driverId, "driverId is required");

        Trip trip = loadAndAssertAssignedDriver(tripId, driverId);
        Instant now = Instant.now();

        trip.startPickingUp(driverId, "DRIVER", now);
        Trip saved = tripRepository.save(trip);

        log.info("Driver {} started pickup for trip {}", driverId, tripId);
        recordOutboxEvent(saved, "TRIP_STATUS_CHANGED", "DRIVER", "Driver started pickup", now);
        return TripResponse.from(saved);
    }

    /**
     * Driver confirms arrival at pickup location (PICKING_UP -> ARRIVED).
     */
    @Transactional
    public TripResponse confirmArrived(UUID tripId, UUID driverId) {
        Objects.requireNonNull(tripId, "tripId is required");
        Objects.requireNonNull(driverId, "driverId is required");

        Trip trip = loadAndAssertAssignedDriver(tripId, driverId);
        Instant now = Instant.now();

        trip.confirmArrived(driverId, "DRIVER", now);
        Trip saved = tripRepository.save(trip);

        log.info("Driver {} arrived at pickup location for trip {}", driverId, tripId);
        recordOutboxEvent(saved, "TRIP_STATUS_CHANGED", "DRIVER", "Driver arrived at pickup location", now);
        return TripResponse.from(saved);
    }

    /**
     * Passenger/goods on board; driver starts trip towards destination (ARRIVED -> IN_TRIP).
     */
    @Transactional
    public TripResponse startTrip(UUID tripId, UUID driverId) {
        Objects.requireNonNull(tripId, "tripId is required");
        Objects.requireNonNull(driverId, "driverId is required");

        Trip trip = loadAndAssertAssignedDriver(tripId, driverId);
        Instant now = Instant.now();

        trip.startTrip(driverId, "DRIVER", now);
        Trip saved = tripRepository.save(trip);

        log.info("Driver {} started in-trip journey for trip {}", driverId, tripId);
        recordOutboxEvent(saved, "TRIP_STATUS_CHANGED", "DRIVER", "Trip in progress", now);
        return TripResponse.from(saved);
    }

    /**
     * Destination reached; driver completes the trip (IN_TRIP -> COMPLETED).
     */
    @Transactional
    public TripResponse completeTrip(UUID tripId, UUID driverId) {
        Objects.requireNonNull(tripId, "tripId is required");
        Objects.requireNonNull(driverId, "driverId is required");

        Trip trip = loadAndAssertAssignedDriver(tripId, driverId);
        Instant now = Instant.now();

        trip.complete(driverId, "DRIVER", now);
        Trip saved = tripRepository.save(trip);

        if (realtimeServiceClient != null) {
            try {
                realtimeServiceClient.revokeTripRoom(tripId);
            } catch (Exception e) {
                log.warn("Fail-safe: Error revoking realtime trip room for completed trip {}: {}", tripId, e.getMessage());
            }
        }

        log.info("Driver {} completed trip {} at destination", driverId, tripId);
        recordOutboxEvent(saved, "TRIP_COMPLETED", "DRIVER", "Trip completed at destination", now);
        return TripResponse.from(saved);
    }

    private Trip loadAndAssertAssignedDriver(UUID tripId, UUID driverId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        if (trip.getDriverId() == null) {
            log.warn("Driver {} attempted to progress unassigned trip {}", driverId, tripId);
            throw new TripAccessDeniedException("Trip " + tripId + " has not been assigned to any driver");
        }

        if (!trip.getDriverId().equals(driverId)) {
            log.warn("Driver {} attempted to progress trip {} assigned to driver {}",
                    driverId, tripId, trip.getDriverId());
            throw new TripAccessDeniedException(tripId, driverId);
        }

        return trip;
    }
}
