package com.rhl.trip.services;

import com.rhl.trip.dtos.CancelTripRequest;
import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
import com.rhl.trip.exceptions.TripAccessDeniedException;
import com.rhl.trip.exceptions.TripNotFoundException;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service orchestrating Trip Cancellation workflows (M05 Group 3, FR-CAN-001..007).
 * Enforces ownership, actor authorization, idempotent retry semantics, and atomic audit logging.
 */
@Service
public class TripCancellationService {

    private static final Logger log = LoggerFactory.getLogger(TripCancellationService.class);

    private final TripRepository tripRepository;
    private final CancellationPolicyService cancellationPolicyService;
    private com.rhl.trip.clients.RealtimeServiceClient realtimeServiceClient;
    private com.rhl.trip.repositories.TripOutboxRepository outboxRepository;
    private tools.jackson.databind.ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public TripCancellationService(
            TripRepository tripRepository,
            CancellationPolicyService cancellationPolicyService,
            com.rhl.trip.repositories.TripOutboxRepository outboxRepository,
            tools.jackson.databind.ObjectMapper objectMapper
    ) {
        this.tripRepository = tripRepository;
        this.cancellationPolicyService = cancellationPolicyService;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public TripCancellationService(
            TripRepository tripRepository,
            CancellationPolicyService cancellationPolicyService) {
        this(tripRepository, cancellationPolicyService, null, new tools.jackson.databind.ObjectMapper());
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
            log.info("Enqueued trip cancellation outbox event {} ({}) for trip {} to customer {}",
                    outboxEvent.getEventId(), eventType, trip.getId(), trip.getCustomerId());
        } catch (Exception ex) {
            log.error("Failed to record trip cancellation outbox event for trip {}: {}", trip.getId(), ex.getMessage(), ex);
            throw new RuntimeException("Error persisting trip outbox event", ex);
        }
    }

    /**
     * Cancels an active trip according to actor role, state permissions, and policy rules.
     *
     * @param tripId    target trip identifier
     * @param actorId   authenticated user ID (from JWT Subject)
     * @param actorRole actor role (CUSTOMER, DRIVER, SUPPORT_STAFF, ADMINISTRATOR)
     * @param request   cancellation reason code and description
     * @return updated TripResponse in CANCELLED status
     */
    @Transactional
    public TripResponse cancelTrip(
            UUID tripId,
            UUID actorId,
            String actorRole,
            CancelTripRequest request) {

        Objects.requireNonNull(tripId, "tripId is required");
        Objects.requireNonNull(actorId, "actorId is required");
        Objects.requireNonNull(actorRole, "actorRole is required");
        Objects.requireNonNull(request, "request is required");

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        // 1. Idempotency Check (FR-CAN-006): Repeated cancellation by the same actor returns existing state
        if (trip.getStatus() == TripStatus.CANCELLED) {
            if (actorId.equals(trip.getCancelledBy())) {
                log.info("Idempotent cancel retry detected for tripId={} by actorId={}. Returning existing cancelled trip.",
                        tripId, actorId);
                return TripResponse.from(trip);
            }
            log.warn("Trip {} is already cancelled by actor {} ({}), cannot be cancelled again by {}",
                    tripId, trip.getCancelledBy(), trip.getCancellationActorRole(), actorId);
            throw new InvalidTripStateTransitionException(
                    TripStatus.CANCELLED, TripStatus.CANCELLED,
                    "Trip is already cancelled by " + trip.getCancellationActorRole()
            );
        }

        // 2. Terminal State Check
        if (trip.getStatus().isTerminal()) {
            throw new InvalidTripStateTransitionException(
                    trip.getStatus(), TripStatus.CANCELLED,
                    "Cannot cancel trip in terminal state " + trip.getStatus()
            );
        }

        // 3. Ownership & Actor Authorization Checks
        String normalizedRole = actorRole.toUpperCase();
        validateActorOwnership(trip, actorId, normalizedRole);

        // 4. In-Trip Exception Workflow Check (SRS 7.1)
        if (trip.getStatus() == TripStatus.IN_TRIP) {
            if (!normalizedRole.equals("SUPPORT_STAFF") && !normalizedRole.equals("ADMINISTRATOR")) {
                throw new InvalidTripStateTransitionException(
                        TripStatus.IN_TRIP, TripStatus.CANCELLED,
                        "Trip in IN_TRIP status can only be cancelled via approved exception workflow by SUPPORT_STAFF or ADMINISTRATOR"
                );
            }
        }

        // 5. Calculate Cancellation Fee via Policy
        Instant now = Instant.now();
        BigDecimal fee = cancellationPolicyService.calculateCancellationFee(
                trip, actorId, normalizedRole, request.reasonCode(), now
        );

        // 6. Format Reason String (FR-CAN-002)
        String formattedReason = request.description() != null && !request.description().isBlank()
                ? request.reasonCode() + ": " + request.description().trim()
                : request.reasonCode();

        // 7. Perform Domain Transition & Append Status History (Atomic via JPA cascade)
        trip.cancel(actorId, normalizedRole, formattedReason, fee, now);

        Trip saved = tripRepository.save(trip);
        log.info("Trip {} successfully cancelled by {} (role={}), fee={}, reason={}",
                tripId, actorId, normalizedRole, fee, formattedReason);

        recordOutboxEvent(saved, "TRIP_CANCELLED", normalizedRole, formattedReason, now);

        if (realtimeServiceClient != null) {
            try {
                realtimeServiceClient.revokeTripRoom(tripId);
            } catch (Exception e) {
                log.warn("Fail-safe: Error revoking realtime trip room for cancelled trip {}: {}", tripId, e.getMessage());
            }
        }

        return TripResponse.from(saved);
    }

    private void validateActorOwnership(Trip trip, UUID actorId, String normalizedRole) {
        switch (normalizedRole) {
            case "CUSTOMER" -> {
                if (!trip.getCustomerId().equals(actorId)) {
                    log.warn("Customer {} attempted to cancel unowned trip {}", actorId, trip.getId());
                    throw new TripAccessDeniedException(trip.getId(), actorId);
                }
            }
            case "DRIVER" -> {
                if (trip.getDriverId() == null || !trip.getDriverId().equals(actorId)) {
                    log.warn("Driver {} attempted to cancel unassigned trip {}", actorId, trip.getId());
                    throw new TripAccessDeniedException(trip.getId(), actorId);
                }
            }
            case "SUPPORT_STAFF", "ADMINISTRATOR" -> {
                // Administrative / support staff override permitted
            }
            default -> throw new TripAccessDeniedException("Role " + normalizedRole + " is not authorized to cancel trips");
        }
    }
}
