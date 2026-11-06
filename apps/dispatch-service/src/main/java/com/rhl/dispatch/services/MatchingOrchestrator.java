package com.rhl.dispatch.services;

import com.rhl.dispatch.configs.MatchingProperties;
import com.rhl.dispatch.dtos.CreateOfferCommand;
import com.rhl.dispatch.dtos.DriverOfferResponse;
import com.rhl.dispatch.entities.DriverOffer;
import com.rhl.dispatch.entities.MatchingSession;
import com.rhl.dispatch.exceptions.DriverOfferConflictException;
import com.rhl.dispatch.models.AssignmentStatus;
import com.rhl.dispatch.models.CandidateSelectionResult;
import com.rhl.dispatch.models.DriverCandidate;
import com.rhl.dispatch.models.MatchingCriteria;
import com.rhl.dispatch.models.MatchingOrchestrationResult;
import com.rhl.dispatch.models.MatchingSessionStatus;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;
import com.rhl.dispatch.models.TripAssignmentResult;
import com.rhl.dispatch.models.TripMatchingCriteriaSnapshot;
import com.rhl.dispatch.models.VehicleType;
import com.rhl.dispatch.providers.TripAssignmentProvider;
import com.rhl.dispatch.providers.TripCriteriaProvider;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import com.rhl.dispatch.repositories.MatchingSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Matching Orchestrator (M06 Group 3, FR-MAT-001..015).
 * Coordinates radius progression, candidate selection, offer dispatch, atomic assignment,
 * offer rejection/timeout handling, and fallback to NO_DRIVER.
 *
 * Designed with strict non-blocking database transactions:
 * HTTP calls to external microservices (Location, Identity, Trip) are NEVER executed inside
 * open PostgreSQL database transactions.
 */
@Service
public class MatchingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MatchingOrchestrator.class);

    private final TripCriteriaProvider tripCriteriaProvider;
    private final TripAssignmentProvider tripAssignmentProvider;
    private final MatchingSessionRepository matchingSessionRepository;
    private final DriverOfferRepository driverOfferRepository;
    private final DriverOfferService driverOfferService;
    private final DriverCandidateSelector driverCandidateSelector;
    private final MatchingProperties matchingProperties;
    private final Clock clock;

    public MatchingOrchestrator(
            TripCriteriaProvider tripCriteriaProvider,
            TripAssignmentProvider tripAssignmentProvider,
            MatchingSessionRepository matchingSessionRepository,
            DriverOfferRepository driverOfferRepository,
            DriverOfferService driverOfferService,
            DriverCandidateSelector driverCandidateSelector,
            MatchingProperties matchingProperties,
            Clock clock
    ) {
        this.tripCriteriaProvider = Objects.requireNonNull(tripCriteriaProvider, "tripCriteriaProvider is required");
        this.tripAssignmentProvider = Objects.requireNonNull(tripAssignmentProvider, "tripAssignmentProvider is required");
        this.matchingSessionRepository = Objects.requireNonNull(matchingSessionRepository, "matchingSessionRepository is required");
        this.driverOfferRepository = Objects.requireNonNull(driverOfferRepository, "driverOfferRepository is required");
        this.driverOfferService = Objects.requireNonNull(driverOfferService, "driverOfferService is required");
        this.driverCandidateSelector = Objects.requireNonNull(driverCandidateSelector, "driverCandidateSelector is required");
        this.matchingProperties = Objects.requireNonNull(matchingProperties, "matchingProperties is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Starts or advances the matching process for a trip (FR-MAT-001..004, FR-TRIP-006).
     *
     * @param tripId authoritative trip ID
     * @return orchestration outcome
     */
    public MatchingOrchestrationResult startOrContinueMatching(UUID tripId) {
        Objects.requireNonNull(tripId, "tripId is required");

        // 1. Authoritative Trip state retrieval (Executed outside DB transaction)
        TripMatchingCriteriaSnapshot tripSnapshot = tripCriteriaProvider.getTripMatchingCriteria(tripId);

        if ("CREATED".equalsIgnoreCase(tripSnapshot.status())) {
            log.info("Trip {} is in CREATED status. Triggering start-matching transition...", tripId);
            tripSnapshot = tripAssignmentProvider.startMatching(tripId);
        }

        if ("ACCEPTED".equalsIgnoreCase(tripSnapshot.status())) {
            log.info("Trip {} is already ACCEPTED. Matching terminated.", tripId);
            return MatchingOrchestrationResult.alreadyMatched(tripId, null);
        }

        if ("CANCELLED".equalsIgnoreCase(tripSnapshot.status()) || "NO_DRIVER".equalsIgnoreCase(tripSnapshot.status())) {
            log.info("Trip {} is in terminal status {}. Matching aborted.", tripId, tripSnapshot.status());
            return MatchingOrchestrationResult.cancelled(tripId, "Trip is in terminal status: " + tripSnapshot.status());
        }

        if (!"MATCHING".equalsIgnoreCase(tripSnapshot.status())) {
            throw new IllegalStateException("Trip " + tripId + " is not in MATCHING status (current: " + tripSnapshot.status() + ")");
        }

        // 2. Check for active winning/pending offer for this trip
        Optional<DriverOffer> winningOffer = driverOfferRepository.findByTripIdAndStatusAndAssignmentStatusIn(
                tripId, OfferStatus.ACCEPTED, List.of(AssignmentStatus.PENDING, AssignmentStatus.ASSIGNED));
        if (winningOffer.isPresent()) {
            log.info("Trip {} already has a winning offer {} assigned/pending to driver {}",
                    tripId, winningOffer.get().getId(), winningOffer.get().getDriverId());
            return MatchingOrchestrationResult.alreadyMatched(tripId, winningOffer.get().getDriverId());
        }

        // Check for active pending offer
        List<DriverOffer> existingOffers = driverOfferRepository.findByTripId(tripId);
        Instant now = clock.instant();
        for (DriverOffer offer : existingOffers) {
            if (offer.getStatus() == OfferStatus.PENDING) {
                if (offer.isExpired(now)) {
                    log.info("Expiring active offer {} for trip {}", offer.getId(), tripId);
                    driverOfferService.expireOffer(offer.getId());
                } else {
                    log.info("Trip {} already has an active pending offer {} for driver {}", tripId, offer.getId(), offer.getDriverId());
                    return MatchingOrchestrationResult.offerDispatched(tripId, offer.getId(), offer.getDriverId(), 1, 1000);
                }
            }
        }

        // 3. Find or create matching session
        MatchingSession session = getOrCreateActiveSession(tripId, now);
        if (session.getStatus() != MatchingSessionStatus.SEARCHING) {
            return MatchingOrchestrationResult.alreadyMatched(tripId, null);
        }

        // 4. Multi-round candidate search loop
        List<Double> radii = matchingProperties.radiiKm();
        int maxRounds = radii.size();

        while (session.getRoundNumber() <= maxRounds) {
            int round = session.getRoundNumber();
            double radiusKm = radii.get(round - 1);
            int radiusMeters = (int) (radiusKm * 1000);

            // Collect drivers previously offered for this trip to avoid re-offering in this session
            Set<UUID> excludedDriverIds = driverOfferRepository.findByTripId(tripId).stream()
                    .map(DriverOffer::getDriverId)
                    .collect(Collectors.toSet());

            MatchingCriteria criteria = MatchingCriteria.builder()
                    .tripId(tripId)
                    .pickupLocation(tripSnapshot.pickupLatitude(), tripSnapshot.pickupLongitude())
                    .serviceType(ServiceType.valueOf(tripSnapshot.serviceType()))
                    .vehicleType(VehicleType.valueOf(tripSnapshot.vehicleType()))
                    .radiusKm(radiusKm)
                    .searchRound(round)
                    .maxCandidates(matchingProperties.maxCandidates())
                    .excludedDriverIds(excludedDriverIds)
                    .build();

            // Execute Candidate Selection (Outside DB transaction)
            CandidateSelectionResult selection = driverCandidateSelector.selectCandidates(criteria);

            if (selection.hasCandidates()) {
                DriverCandidate candidate = selection.firstCandidate().get();
                log.info("Selected candidate driver {} for trip {} (round={}, radius={}km)",
                        candidate.driverId(), tripId, round, radiusKm);

                try {
                    CreateOfferCommand cmd = new CreateOfferCommand(
                            tripId,
                            candidate.driverId(),
                            candidate.vehicleId(),
                            criteria.serviceType(),
                            null
                    );
                    DriverOfferResponse offerResponse = driverOfferService.createOffer(cmd);
                    return MatchingOrchestrationResult.offerDispatched(
                            tripId, offerResponse.id(), candidate.driverId(), round, radiusMeters);

                } catch (DriverOfferConflictException conflictEx) {
                    log.warn("Offer conflict dispatching to driver {}: {}. Skipping to next round/candidate.",
                            candidate.driverId(), conflictEx.getMessage());
                }
            }

            // No eligible candidate in this round: advance round or exhaust
            if (round < maxRounds) {
                int nextRound = round + 1;
                int nextRadiusMeters = (int) (radii.get(nextRound - 1) * 1000);
                log.info("No candidates for trip {} in round {} ({}km). Advancing to round {} ({}km)...",
                        tripId, round, radiusKm, nextRound, radii.get(nextRound - 1));

                session.advanceRound(nextRound, nextRadiusMeters, now);
                MatchingSession savedSession = matchingSessionRepository.save(session);
                if (savedSession != null) {
                    session = savedSession;
                }
            } else {
                // All rounds exhausted: finalize session as EXPIRED and notify Trip Service NO_DRIVER
                log.warn("Exhausted all {} matching rounds for trip {}. Transitioning to NO_DRIVER...", maxRounds, tripId);
                session.complete(MatchingSessionStatus.EXPIRED, now);
                MatchingSession savedSession = matchingSessionRepository.save(session);
                if (savedSession != null) {
                    session = savedSession;
                }

                tripAssignmentProvider.markNoDriver(tripId, "Candidates exhausted across all matching rounds");
                return MatchingOrchestrationResult.noDriver(tripId, round, radiusMeters, "All rounds exhausted");
            }
        }

        return MatchingOrchestrationResult.noDriver(tripId, maxRounds, 5000, "Exhausted all matching rounds");
    }

    /**
     * Driver accepts an offer, triggering atomic cross-service trip assignment (FR-MAT-010, FR-MAT-014).
     */
    public MatchingOrchestrationResult acceptOffer(UUID offerId, UUID driverId) {
        Objects.requireNonNull(offerId, "offerId is required");
        Objects.requireNonNull(driverId, "driverId is required");

        // Step 1: Accept offer locally in Dispatch DB (PENDING -> ACCEPTED, assignment_status = PENDING)
        DriverOfferResponse offerResponse = driverOfferService.acceptOffer(offerId, driverId);
        UUID tripId = offerResponse.tripId();
        Instant now = clock.instant();

        // Step 2: Atomic M2M Trip Assignment via HTTP (Executed OUTSIDE database transaction)
        TripAssignmentResult assignmentResult = tripAssignmentProvider.assignDriver(
                tripId, driverId, offerResponse.vehicleId(), offerId);

        if (assignmentResult.success()) {
            log.info("Trip {} successfully assigned to driver {} via M2M. Finalizing dispatch state...", tripId, driverId);

            // Step 3a: Mark offer as ASSIGNED in Dispatch DB
            driverOfferService.markAssignmentAssigned(offerId);

            // Close matching session
            matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING)
                    .ifPresent(session -> {
                        session.complete(MatchingSessionStatus.MATCHED, now);
                        matchingSessionRepository.save(session);
                    });

            // Revoke any other pending offers for this trip
            driverOfferService.revokeAllPendingOffersForTrip(tripId, "Trip assigned to winning driver", "SYSTEM");

            return MatchingOrchestrationResult.alreadyMatched(tripId, driverId);
        } else {
            // Step 3b: Assignment failed or rejected by Trip Service
            log.warn("Trip assignment failed for trip {} and driver {}: [{}] {}",
                    tripId, driverId, assignmentResult.errorCode(), assignmentResult.errorMessage());

            driverOfferService.markAssignmentFailed(offerId, assignmentResult.errorMessage());

            if ("TRIP_CANCELLED".equalsIgnoreCase(assignmentResult.errorCode())) {
                matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING)
                        .ifPresent(session -> {
                            session.complete(MatchingSessionStatus.CANCELLED, now);
                            matchingSessionRepository.save(session);
                        });
                driverOfferService.revokeAllPendingOffersForTrip(tripId, "Trip was cancelled", "SYSTEM");
                return MatchingOrchestrationResult.cancelled(tripId, "Trip was cancelled");
            }

            if ("CONFLICT_ANOTHER_DRIVER".equalsIgnoreCase(assignmentResult.errorCode())) {
                matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING)
                        .ifPresent(session -> {
                            session.complete(MatchingSessionStatus.MATCHED, now);
                            matchingSessionRepository.save(session);
                        });
                driverOfferService.revokeAllPendingOffersForTrip(tripId, "Trip assigned to another driver", "SYSTEM");
                return MatchingOrchestrationResult.alreadyMatched(tripId, null);
            }

            // If assignment failed due to driver busy or transient error, resume matching for the trip!
            log.info("Winner slot released for trip {}. Resuming matching for next candidate...", tripId);
            return startOrContinueMatching(tripId);
        }
    }

    /**
     * Driver rejects an offer (PENDING -> REJECTED), triggering progression to next candidate (FR-MAT-012).
     */
    public MatchingOrchestrationResult rejectOffer(UUID offerId, UUID driverId, String reason) {
        Objects.requireNonNull(offerId, "offerId is required");
        Objects.requireNonNull(driverId, "driverId is required");

        DriverOfferResponse offer = driverOfferService.rejectOffer(offerId, driverId, reason);
        log.info("Offer {} rejected by driver {}. Continuing matching for trip {}...", offerId, driverId, offer.tripId());

        return startOrContinueMatching(offer.tripId());
    }

    /**
     * Offer expires on timeout (PENDING -> EXPIRED), triggering progression to next candidate (FR-MAT-013).
     */
    public MatchingOrchestrationResult expireOffer(UUID offerId) {
        Objects.requireNonNull(offerId, "offerId is required");

        DriverOfferResponse offer = driverOfferService.expireOffer(offerId);
        log.info("Offer {} expired. Continuing matching for trip {}...", offerId, offer.tripId());

        return startOrContinueMatching(offer.tripId());
    }

    /**
     * Customer or System cancels trip during matching (FR-CAN-001..007).
     */
    public MatchingOrchestrationResult cancelMatching(UUID tripId, String reason) {
        Objects.requireNonNull(tripId, "tripId is required");

        Instant now = clock.instant();
        matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING)
                .ifPresent(session -> {
                    session.complete(MatchingSessionStatus.CANCELLED, now);
                    matchingSessionRepository.save(session);
                });

        driverOfferService.revokeAllPendingOffersForTrip(tripId, reason, "SYSTEM");
        log.info("Matching cancelled for trip {} (reason: {})", tripId, reason);

        return MatchingOrchestrationResult.cancelled(tripId, reason);
    }

    private MatchingSession getOrCreateActiveSession(UUID tripId, Instant now) {
        Optional<MatchingSession> activeOpt = matchingSessionRepository.findByTripIdAndStatus(
                tripId, MatchingSessionStatus.SEARCHING);
        if (activeOpt.isPresent()) {
            return activeOpt.get();
        }

        MatchingSession newSession = new MatchingSession(
                UUID.randomUUID(),
                tripId,
                1,
                1000,
                MatchingSessionStatus.SEARCHING,
                now
        );
        return matchingSessionRepository.save(newSession);
    }
}
