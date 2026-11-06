package com.rhl.dispatch.services;

import com.rhl.dispatch.entities.DriverOffer;
import com.rhl.dispatch.entities.MatchingSession;
import com.rhl.dispatch.models.AssignmentStatus;
import com.rhl.dispatch.models.MatchingSessionStatus;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.TripAssignmentResult;
import com.rhl.dispatch.providers.TripAssignmentProvider;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import com.rhl.dispatch.repositories.MatchingSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Dispatch Recovery Service (M06 Group 4, P0 Recovery & Durability).
 * Handles recovery of stale PENDING assignments after crash/restart/network partition,
 * expiration of overdue PENDING offers, and recovery of orphaned SEARCHING matching sessions.
 *
 * Implements multi-instance safety via PostgreSQL locking / lease fencing.
 */
@Service
public class DispatchRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(DispatchRecoveryService.class);

    private final DriverOfferRepository driverOfferRepository;
    private final MatchingSessionRepository matchingSessionRepository;
    private final DriverOfferService driverOfferService;
    private final MatchingOrchestrator matchingOrchestrator;
    private final TripAssignmentProvider tripAssignmentProvider;
    private final Clock clock;

    public DispatchRecoveryService(
            DriverOfferRepository driverOfferRepository,
            MatchingSessionRepository matchingSessionRepository,
            DriverOfferService driverOfferService,
            MatchingOrchestrator matchingOrchestrator,
            TripAssignmentProvider tripAssignmentProvider,
            Clock clock
    ) {
        this.driverOfferRepository = Objects.requireNonNull(driverOfferRepository, "driverOfferRepository is required");
        this.matchingSessionRepository = Objects.requireNonNull(matchingSessionRepository, "matchingSessionRepository is required");
        this.driverOfferService = Objects.requireNonNull(driverOfferService, "driverOfferService is required");
        this.matchingOrchestrator = Objects.requireNonNull(matchingOrchestrator, "matchingOrchestrator is required");
        this.tripAssignmentProvider = Objects.requireNonNull(tripAssignmentProvider, "tripAssignmentProvider is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Scans for driver offers in ACCEPTED status with assignmentStatus = PENDING that have exceeded the stale threshold.
     * Reconciles authoritative state with Trip Service and completes the assignment or fails appropriately.
     *
     * @param staleThreshold minimum duration since last offer update to be eligible for recovery
     * @return count of recovered assignments
     */
    public int recoverStalePendingAssignments(Duration staleThreshold) {
        Instant now = clock.instant();
        Instant thresholdInstant = now.minus(staleThreshold);

        List<DriverOffer> pendingOffers = driverOfferRepository.findPendingAssignmentsForRecovery(thresholdInstant);
        if (pendingOffers == null || pendingOffers.isEmpty()) {
            return 0;
        }

        log.info("Found {} stale PENDING assignments requiring reconciliation (older than {})", pendingOffers.size(), thresholdInstant);
        int recoveredCount = 0;

        for (DriverOffer offer : pendingOffers) {
            try {
                reconcilePendingOffer(offer.getId(), offer.getTripId(), offer.getDriverId(), offer.getVehicleId(), now);
                recoveredCount++;
            } catch (Exception ex) {
                log.error("Failed to recover stale pending offer {}: {}", offer.getId(), ex.getMessage(), ex);
            }
        }

        return recoveredCount;
    }

    @Transactional
    public void reconcilePendingOffer(UUID offerId, UUID tripId, UUID driverId, UUID vehicleId, Instant now) {
        DriverOffer offer = driverOfferRepository.findById(offerId).orElse(null);
        if (offer == null || offer.getStatus() != OfferStatus.ACCEPTED || offer.getAssignmentStatus() != AssignmentStatus.PENDING) {
            // Already resolved by concurrent worker or terminal
            return;
        }

        log.info("Reconciling offer {} for trip {} and driver {}...", offerId, tripId, driverId);

        try {
            TripAssignmentResult result = tripAssignmentProvider.assignDriver(tripId, driverId, vehicleId, offerId);

            if (result.success()) {
                log.info("Reconciliation SUCCESS for trip {} and driver {}. Marking offer ASSIGNED.", tripId, driverId);
                driverOfferService.markAssignmentAssigned(offerId);

                matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING)
                        .ifPresent(s -> {
                            s.complete(MatchingSessionStatus.MATCHED, now);
                            matchingSessionRepository.save(s);
                        });

                driverOfferService.revokeAllPendingOffersForTrip(tripId, "Trip assigned to winning driver", "SYSTEM");
            } else {
                log.warn("Reconciliation FAILED for trip {} and driver {}: [{}] {}",
                        tripId, driverId, result.errorCode(), result.errorMessage());

                driverOfferService.markAssignmentFailed(offerId, result.errorMessage());

                if ("TRIP_CANCELLED".equalsIgnoreCase(result.errorCode())) {
                    matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING)
                            .ifPresent(s -> {
                                s.complete(MatchingSessionStatus.CANCELLED, now);
                                matchingSessionRepository.save(s);
                            });
                    driverOfferService.revokeAllPendingOffersForTrip(tripId, "Trip was cancelled", "SYSTEM");
                } else if ("CONFLICT_ANOTHER_DRIVER".equalsIgnoreCase(result.errorCode())) {
                    matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING)
                            .ifPresent(s -> {
                                s.complete(MatchingSessionStatus.MATCHED, now);
                                matchingSessionRepository.save(s);
                            });
                    driverOfferService.revokeAllPendingOffersForTrip(tripId, "Trip assigned to another driver", "SYSTEM");
                } else {
                    matchingOrchestrator.startOrContinueMatching(tripId);
                }
            }
        } catch (Exception ex) {
            log.warn("Trip Service unreachable during reconciliation for offer {}. Leaving in PENDING for future retry: {}", offerId, ex.getMessage());
            // Intentionally preserve PENDING state (Case 6)
        }
    }

    /**
     * Scans for expired PENDING offers and transitions them to EXPIRED, resuming matching progression.
     *
     * @return count of expired offers handled
     */
    public int recoverExpiredOffers() {
        Instant now = clock.instant();
        List<DriverOffer> expired = driverOfferRepository.findExpiredOffers(OfferStatus.PENDING, now);
        if (expired == null || expired.isEmpty()) {
            return 0;
        }

        log.info("Found {} expired offers requiring timeout processing", expired.size());
        int count = 0;
        for (DriverOffer offer : expired) {
            try {
                matchingOrchestrator.expireOffer(offer.getId());
                count++;
            } catch (Exception ex) {
                log.error("Failed to expire offer {}: {}", offer.getId(), ex.getMessage(), ex);
            }
        }
        return count;
    }

    /**
     * Scans for orphaned SEARCHING matching sessions with no pending or winning offer and resumes matching.
     *
     * @param staleThreshold minimum duration of inactivity
     * @return count of resumed sessions
     */
    public int recoverOrphanedMatchingSessions(Duration staleThreshold) {
        Instant now = clock.instant();
        Instant thresholdInstant = now.minus(staleThreshold);

        List<MatchingSession> searchingSessions = matchingSessionRepository.findByStatus(MatchingSessionStatus.SEARCHING);
        if (searchingSessions == null || searchingSessions.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (MatchingSession session : searchingSessions) {
            if (session.getUpdatedAt() != null && session.getUpdatedAt().isBefore(thresholdInstant)) {
                // Check if any offer is currently PENDING or winning
                boolean hasActiveOffer = driverOfferRepository.existsByTripIdAndStatus(session.getTripId(), OfferStatus.PENDING)
                        || driverOfferRepository.existsByTripIdAndStatusAndAssignmentStatusIn(
                                session.getTripId(), OfferStatus.ACCEPTED, List.of(AssignmentStatus.PENDING, AssignmentStatus.ASSIGNED));

                if (!hasActiveOffer) {
                    log.info("Resuming orphaned SEARCHING matching session for trip {}...", session.getTripId());
                    try {
                        matchingOrchestrator.startOrContinueMatching(session.getTripId());
                        count++;
                    } catch (Exception ex) {
                        log.error("Failed to resume matching for trip {}: {}", session.getTripId(), ex.getMessage(), ex);
                    }
                }
            }
        }
        return count;
    }
}
