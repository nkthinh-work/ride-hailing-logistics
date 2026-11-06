package com.rhl.trip.services;

import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
import com.rhl.trip.exceptions.TripNotFoundException;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Dispatch Readiness Interface / Boundary (M05 Group 3, M06 Group 3, DR-001, FR-MAT-010).
 * Handles atomic trip assignment, start-matching trigger, and no-driver exhaustion.
 */
@Service
public class DispatchTripAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(DispatchTripAssignmentService.class);

    private static final Set<TripStatus> ACTIVE_DRIVER_STATUSES = Set.of(
            TripStatus.ACCEPTED,
            TripStatus.PICKING_UP,
            TripStatus.ARRIVED,
            TripStatus.IN_TRIP
    );

    private final TripRepository tripRepository;
    private final com.rhl.trip.repositories.TripOutboxRepository outboxRepository;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public DispatchTripAssignmentService(
            TripRepository tripRepository,
            com.rhl.trip.repositories.TripOutboxRepository outboxRepository,
            tools.jackson.databind.ObjectMapper objectMapper
    ) {
        this.tripRepository = tripRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public DispatchTripAssignmentService(TripRepository tripRepository) {
        this(tripRepository, null, new tools.jackson.databind.ObjectMapper());
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
     * Assigns driver and vehicle upon dispatch offer acceptance (MATCHING -> ACCEPTED).
     * Enforces:
     * - Idempotency: repeated calls with same driver/vehicle return 200 OK
     * - Conflict: if already assigned to a different driver, throws 409
     * - State guard: can only assign from MATCHING status
     * - Concurrency: ensures driver has no other active trips
     *
     * @param tripId    the trip to assign
     * @param driverId  assigned driver ID
     * @param vehicleId assigned vehicle ID
     * @param actorRole actor role (typically DRIVER or SYSTEM)
     * @return updated TripResponse in ACCEPTED status
     */
    @Transactional
    public TripResponse assignDriver(UUID tripId, UUID driverId, UUID vehicleId, String actorRole) {
        Objects.requireNonNull(tripId, "tripId is required");
        Objects.requireNonNull(driverId, "driverId is required");
        String role = actorRole != null ? actorRole : "DRIVER";

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        // 1. Idempotency Check: if already accepted with same driver and vehicle, return existing state
        if (trip.getStatus() == TripStatus.ACCEPTED) {
            if (driverId.equals(trip.getDriverId()) && Objects.equals(vehicleId, trip.getVehicleId())) {
                log.info("Trip {} is already assigned to driver {} (vehicle={}). Idempotent replay.", tripId, driverId, vehicleId);
                return TripResponse.from(trip);
            } else {
                throw new InvalidTripStateTransitionException(
                        trip.getStatus(),
                        TripStatus.ACCEPTED,
                        "Trip " + tripId + " is already assigned to a different driver: " + trip.getDriverId()
                );
            }
        }

        // 2. Strict State Transition check: Must be in MATCHING
        if (trip.getStatus() != TripStatus.MATCHING) {
            throw new InvalidTripStateTransitionException(
                    trip.getStatus(),
                    TripStatus.ACCEPTED,
                    "Cannot assign driver to trip " + tripId + " in non-MATCHING status: " + trip.getStatus()
            );
        }

        // 3. Driver Single Active Trip Pre-check (backed by DB partial unique index uk_trips_driver_single_active)
        if (tripRepository.existsByDriverIdAndStatusIn(driverId, ACTIVE_DRIVER_STATUSES)) {
            throw new InvalidTripStateTransitionException(
                    trip.getStatus(),
                    TripStatus.ACCEPTED,
                    "Driver " + driverId + " already has an active trip in progress"
            );
        }

        Instant now = Instant.now();
        trip.accept(driverId, vehicleId, role, now);

        Trip saved = tripRepository.save(trip);
        log.info("Trip {} successfully assigned to driver {} (vehicle={})", tripId, driverId, vehicleId);
        recordOutboxEvent(saved, "TRIP_DRIVER_ASSIGNED", role, "Driver assigned to trip", now);

        return TripResponse.from(saved);
    }

    /**
     * Transitions trip from CREATED to MATCHING when dispatch process starts (FR-TRIP-006).
     */
    @Transactional
    public TripResponse startMatching(UUID tripId) {
        Objects.requireNonNull(tripId, "tripId is required");

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        if (trip.getStatus() == TripStatus.MATCHING) {
            log.info("Trip {} is already in MATCHING status. Idempotent return.", tripId);
            return TripResponse.from(trip);
        }

        Instant now = Instant.now();
        trip.startMatching("SYSTEM");
        Trip saved = tripRepository.save(trip);
        log.info("Trip {} successfully transitioned from CREATED to MATCHING", tripId);
        recordOutboxEvent(saved, "TRIP_STATUS_CHANGED", "SYSTEM", "Matching started", now);

        return TripResponse.from(saved);
    }

    /**
     * Transitions trip from MATCHING to NO_DRIVER when candidate matching is exhausted (FR-MAT-015).
     */
    @Transactional
    public TripResponse markNoDriver(UUID tripId, String reason) {
        Objects.requireNonNull(tripId, "tripId is required");

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        if (trip.getStatus() == TripStatus.NO_DRIVER) {
            log.info("Trip {} is already in NO_DRIVER status. Idempotent return.", tripId);
            return TripResponse.from(trip);
        }

        Instant now = Instant.now();
        trip.markNoDriver("SYSTEM", now);
        Trip saved = tripRepository.save(trip);
        log.info("Trip {} successfully marked as NO_DRIVER (reason: {})", tripId, reason);
        recordOutboxEvent(saved, "TRIP_STATUS_CHANGED", "SYSTEM", reason, now);

        return TripResponse.from(saved);
    }
}

