package com.rhl.dispatch.services;

import com.rhl.dispatch.configs.DispatchProperties;
import com.rhl.dispatch.dtos.CreateOfferCommand;
import com.rhl.dispatch.dtos.DriverOfferResponse;
import com.rhl.dispatch.entities.DriverOffer;
import com.rhl.dispatch.exceptions.DriverOfferConflictException;
import com.rhl.dispatch.exceptions.DriverOfferNotFoundException;
import com.rhl.dispatch.exceptions.InvalidOfferStateTransitionException;
import com.rhl.dispatch.exceptions.OfferAccessDeniedException;
import com.rhl.dispatch.exceptions.OfferExpiredException;
import com.rhl.dispatch.models.AssignmentStatus;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Driver Offer Domain Service (M06 Group 1).
 * Handles offer creation, single pending driver check, atomic offer acceptance,
 * rejection, expiration timeout, revocation, and idempotency.
 */
@Service
public class DriverOfferService {

    private static final Logger log = LoggerFactory.getLogger(DriverOfferService.class);

    private final DriverOfferRepository offerRepository;
    private final DispatchProperties properties;
    private final Clock clock;
    private final com.rhl.dispatch.repositories.DispatchOutboxRepository outboxRepository;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public DriverOfferService(DriverOfferRepository offerRepository,
                              DispatchProperties properties,
                              Clock clock,
                              com.rhl.dispatch.repositories.DispatchOutboxRepository outboxRepository,
                              tools.jackson.databind.ObjectMapper objectMapper) {
        this.offerRepository = offerRepository;
        this.properties = properties;
        this.clock = clock;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public DriverOfferService(DriverOfferRepository offerRepository,
                              DispatchProperties properties,
                              Clock clock) {
        this(offerRepository, properties, clock, null, new tools.jackson.databind.ObjectMapper());
    }

    private void recordOutboxEvent(DriverOffer offer, String eventType, Instant now) {
        if (outboxRepository == null) {
            return;
        }
        try {
            com.rhl.dispatch.events.DispatchOfferPayload payload = new com.rhl.dispatch.events.DispatchOfferPayload(
                    offer.getId(),
                    offer.getTripId(),
                    offer.getDriverId(),
                    offer.getServiceType() != null ? offer.getServiceType().name() : "RIDE",
                    0.0,
                    0.0,
                    null,
                    null,
                    null,
                    null,
                    offer.getOfferedAt(),
                    offer.getExpiresAt(),
                    offer.getStatus().name()
            );

            String jsonPayload = objectMapper != null ? objectMapper.writeValueAsString(payload) : "{}";
            com.rhl.dispatch.entities.DispatchOutboxEvent outboxEvent = new com.rhl.dispatch.entities.DispatchOutboxEvent(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    eventType,
                    "DRIVER_OFFER",
                    offer.getId(),
                    offer.getDriverId(),
                    "corr-offer-" + offer.getId(),
                    jsonPayload,
                    now
            );
            outboxRepository.save(outboxEvent);
            log.info("Enqueued outbox event {} ({}) for offer {} to driver {}",
                    outboxEvent.getEventId(), eventType, offer.getId(), offer.getDriverId());
        } catch (Exception ex) {
            log.error("Failed to record outbox event for offer {}: {}", offer.getId(), ex.getMessage(), ex);
            throw new RuntimeException("Error persisting outbox event", ex);
        }
    }

    /**
     * Creates a new driver offer in PENDING status.
     * Enforces the invariant that a driver cannot have more than 1 PENDING offer concurrently (FR-DRV-012).
     */
    @Transactional
    public DriverOfferResponse createOffer(CreateOfferCommand command) {
        Objects.requireNonNull(command, "CreateOfferCommand is required");
        Objects.requireNonNull(command.tripId(), "tripId is required");
        Objects.requireNonNull(command.driverId(), "driverId is required");
        Objects.requireNonNull(command.serviceType(), "serviceType is required");

        // 1. Invariant: Trip cannot receive new offers if it already has an active winning accepted offer
        if (offerRepository.existsByTripIdAndStatusAndAssignmentStatusIn(
                command.tripId(), OfferStatus.ACCEPTED, List.of(AssignmentStatus.PENDING, AssignmentStatus.ASSIGNED))) {
            throw new DriverOfferConflictException(
                    "TRIP_ALREADY_ACCEPTED",
                    "Trip " + command.tripId() + " already has a winning accepted offer"
            );
        }

        // 2. Invariant: Driver cannot have multiple PENDING offers concurrently (FR-DRV-012)
        if (offerRepository.existsByDriverIdAndStatus(command.driverId(), OfferStatus.PENDING)) {
            throw new DriverOfferConflictException(
                    "DRIVER_ALREADY_HAS_PENDING_OFFER",
                    "Driver " + command.driverId() + " already has an active pending offer"
            );
        }

        Instant now = clock.instant();
        Duration ttl = command.ttl() != null ? command.ttl() : Duration.ofSeconds(properties.defaultTtlSeconds());
        Instant expiresAt = now.plus(ttl);

        DriverOffer offer = DriverOffer.builder()
                .id(UUID.randomUUID())
                .tripId(command.tripId())
                .driverId(command.driverId())
                .vehicleId(command.vehicleId())
                .serviceType(command.serviceType())
                .offeredAt(now)
                .expiresAt(expiresAt)
                .actor(command.driverId(), "SYSTEM")
                .build();

        DriverOffer saved = offerRepository.save(offer);
        log.info("Created offer {} for trip {} to driver {} (expiresAt={})", saved.getId(), saved.getTripId(), saved.getDriverId(), saved.getExpiresAt());
        recordOutboxEvent(saved, "DRIVER_OFFER_CREATED", now);

        return DriverOfferResponse.from(saved);
    }

    /**
     * Driver accepts an offer (PENDING -> ACCEPTED).
     * Enforces:
     * - Driver ownership check
     * - Idempotent replay for repeat acceptance by same driver
     * - Server-authoritative expiration check
     * - Only one winning offer per trip (PostgreSQL Partial Unique Index + pre-check)
     */
    @Transactional
    public DriverOfferResponse acceptOffer(UUID offerId, UUID driverId) {
        Objects.requireNonNull(offerId, "offerId is required");
        Objects.requireNonNull(driverId, "driverId is required");

        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new DriverOfferNotFoundException(offerId));

        // 1. Actor guard: Driver can only accept offer addressed to themselves
        if (!offer.getDriverId().equals(driverId)) {
            throw new OfferAccessDeniedException(offerId, driverId);
        }

        // 2. Idempotent replay: if already accepted by this driver, return existing state
        if (offer.getStatus() == OfferStatus.ACCEPTED) {
            log.info("Offer {} was already accepted by driver {}. Returning idempotent response.", offerId, driverId);
            return DriverOfferResponse.from(offer);
        }

        // 3. Terminal state rejection
        if (offer.getStatus().isTerminal()) {
            throw new InvalidOfferStateTransitionException(
                    offer.getStatus(),
                    OfferStatus.ACCEPTED,
                    "DRIVER",
                    "Offer is already resolved in terminal state: " + offer.getStatus()
            );
        }

        // 4. Authoritative server time expiry check
        Instant now = clock.instant();
        if (offer.isExpired(now)) {
            offer.expire(now);
            offerRepository.save(offer);
            throw new OfferExpiredException(offerId, offer.getExpiresAt(), now);
        }

        // 5. Pre-check: Ensure no other offer for this trip has already been accepted and pending/assigned
        if (offerRepository.existsByTripIdAndStatusAndAssignmentStatusIn(
                offer.getTripId(), OfferStatus.ACCEPTED, List.of(AssignmentStatus.PENDING, AssignmentStatus.ASSIGNED))) {
            throw new DriverOfferConflictException(
                    "ANOTHER_DRIVER_ALREADY_ACCEPTED",
                    "Another driver has already accepted the trip offer."
            );
        }

        // 6. Transition to ACCEPTED (sets assignmentStatus = PENDING)
        offer.accept(driverId, now);
        DriverOffer saved = offerRepository.save(offer);
        log.info("Offer {} accepted by driver {} for trip {}", offerId, driverId, saved.getTripId());
        recordOutboxEvent(saved, "DRIVER_OFFER_ACCEPTED", now);

        return DriverOfferResponse.from(saved);
    }

    /**
     * Marks offer assignment as ASSIGNED upon confirmed trip assignment in Trip Service.
     */
    @Transactional
    public DriverOfferResponse markAssignmentAssigned(UUID offerId) {
        Objects.requireNonNull(offerId, "offerId is required");
        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new DriverOfferNotFoundException(offerId));

        Instant now = clock.instant();
        offer.markAssigned(now);
        DriverOffer saved = offerRepository.save(offer);
        log.info("Offer {} marked as ASSIGNED for trip {}", offerId, saved.getTripId());
        recordOutboxEvent(saved, "DRIVER_OFFER_ASSIGNMENT_CONFIRMED", now);
        return DriverOfferResponse.from(saved);
    }

    /**
     * Marks offer assignment as FAILED upon definitive failure in Trip Service.
     */
    @Transactional
    public DriverOfferResponse markAssignmentFailed(UUID offerId, String reason) {
        Objects.requireNonNull(offerId, "offerId is required");
        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new DriverOfferNotFoundException(offerId));

        Instant now = clock.instant();
        offer.markAssignmentFailed(reason, now);
        DriverOffer saved = offerRepository.save(offer);
        log.warn("Offer {} marked as assignment FAILED for trip {} (reason: {})", offerId, saved.getTripId(), reason);
        return DriverOfferResponse.from(saved);
    }

    /**
     * Driver rejects an offer (PENDING -> REJECTED).
     */
    @Transactional
    public DriverOfferResponse rejectOffer(UUID offerId, UUID driverId, String reason) {
        Objects.requireNonNull(offerId, "offerId is required");
        Objects.requireNonNull(driverId, "driverId is required");

        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new DriverOfferNotFoundException(offerId));

        // 1. Actor guard
        if (!offer.getDriverId().equals(driverId)) {
            throw new OfferAccessDeniedException(offerId, driverId);
        }

        // 2. Idempotent replay: if already rejected by this driver, return existing state
        if (offer.getStatus() == OfferStatus.REJECTED) {
            log.info("Offer {} was already rejected by driver {}. Returning idempotent response.", offerId, driverId);
            return DriverOfferResponse.from(offer);
        }

        // 3. Terminal state rejection
        if (offer.getStatus().isTerminal()) {
            throw new InvalidOfferStateTransitionException(
                    offer.getStatus(),
                    OfferStatus.REJECTED,
                    "DRIVER",
                    "Offer is already resolved in terminal state: " + offer.getStatus()
            );
        }

        // 4. Server expiry check
        Instant now = clock.instant();
        if (offer.isExpired(now)) {
            offer.expire(now);
            offerRepository.save(offer);
            throw new OfferExpiredException(offerId, offer.getExpiresAt(), now);
        }

        // 5. Transition to REJECTED
        offer.reject(driverId, reason, now);
        DriverOffer saved = offerRepository.save(offer);
        log.info("Offer {} rejected by driver {} (reason={})", offerId, driverId, reason);
        recordOutboxEvent(saved, "DRIVER_OFFER_REJECTED", now);

        return DriverOfferResponse.from(saved);
    }

    /**
     * System marks an offer as EXPIRED upon timeout.
     */
    @Transactional
    public DriverOfferResponse expireOffer(UUID offerId) {
        Objects.requireNonNull(offerId, "offerId is required");

        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new DriverOfferNotFoundException(offerId));

        if (offer.getStatus().isTerminal()) {
            return DriverOfferResponse.from(offer);
        }

        Instant now = clock.instant();
        offer.expire(now);
        DriverOffer saved = offerRepository.save(offer);
        log.info("Offer {} expired by system at {}", offerId, now);
        recordOutboxEvent(saved, "DRIVER_OFFER_EXPIRED", now);

        return DriverOfferResponse.from(saved);
    }

    /**
     * Revokes an offer (e.g. Trip cancelled or another candidate won).
     */
    @Transactional
    public DriverOfferResponse revokeOffer(UUID offerId, String reason, String actorRole) {
        Objects.requireNonNull(offerId, "offerId is required");

        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new DriverOfferNotFoundException(offerId));

        if (offer.getStatus().isTerminal()) {
            return DriverOfferResponse.from(offer);
        }

        Instant now = clock.instant();
        offer.revoke(reason, actorRole != null ? actorRole : "SYSTEM", now);
        DriverOffer saved = offerRepository.save(offer);
        log.info("Offer {} revoked by {} (reason={})", offerId, actorRole, reason);
        recordOutboxEvent(saved, "DRIVER_OFFER_REVOKED", now);

        return DriverOfferResponse.from(saved);
    }

    /**
     * Revokes all active pending offers for a trip (e.g. when Trip is cancelled or winning offer accepted).
     */
    @Transactional
    public void revokeAllPendingOffersForTrip(UUID tripId, String reason, String actorRole) {
        Objects.requireNonNull(tripId, "tripId is required");

        List<DriverOffer> offers = offerRepository.findByTripId(tripId);
        Instant now = clock.instant();
        String role = actorRole != null ? actorRole : "SYSTEM";

        for (DriverOffer offer : offers) {
            if (offer.getStatus() == OfferStatus.PENDING) {
                offer.revoke(reason, role, now);
                offerRepository.save(offer);
                recordOutboxEvent(offer, "DRIVER_OFFER_REVOKED", now);
                log.info("Revoked pending offer {} for trip {}", offer.getId(), tripId);
            }
        }
    }

    /**
     * Retrieves the active pending offer for a driver, checking expiry on-the-fly.
     */
    @Transactional
    public Optional<DriverOfferResponse> getPendingOfferForDriver(UUID driverId) {
        Objects.requireNonNull(driverId, "driverId is required");

        Optional<DriverOffer> opt = offerRepository.findByDriverIdAndStatus(driverId, OfferStatus.PENDING);
        if (opt.isEmpty()) {
            return Optional.empty();
        }

        DriverOffer offer = opt.get();
        Instant now = clock.instant();
        if (offer.isExpired(now)) {
            // Lazy expiration on read
            offer.expire(now);
            offerRepository.save(offer);
            return Optional.empty();
        }

        return Optional.of(DriverOfferResponse.from(offer));
    }

    /**
     * Finds offer by ID.
     */
    @Transactional(readOnly = true)
    public DriverOfferResponse getOfferById(UUID offerId) {
        Objects.requireNonNull(offerId, "offerId is required");
        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new DriverOfferNotFoundException(offerId));
        return DriverOfferResponse.from(offer);
    }
}
