package com.rhl.dispatch.services;

import com.rhl.dispatch.configs.MatchingProperties;
import com.rhl.dispatch.models.AssignmentStatus;
import com.rhl.dispatch.models.CandidateExclusionReason;
import com.rhl.dispatch.models.CandidateSelectionResult;
import com.rhl.dispatch.models.DriverCandidate;
import com.rhl.dispatch.models.MatchingCriteria;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.providers.DriverAvailabilityProvider;
import com.rhl.dispatch.providers.DriverEligibilityProvider;
import com.rhl.dispatch.providers.DriverEligibilityProvider.DriverEligibilityInfo;
import com.rhl.dispatch.providers.DriverEligibilityProvider.VehicleSummary;
import com.rhl.dispatch.providers.LocationNearbyProvider;
import com.rhl.dispatch.providers.LocationNearbyProvider.NearbyDriverLocation;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core candidate selection engine for Driver Matching (FR-MAT-001..005, FR-DRV-008..014).
 * Orchestrates spatial filtering, GPS freshness validation, eligibility checks, vehicle compatibility,
 * and operational availability in a strictly fail-closed, deterministic pipeline.
 */
@Service
public class DriverCandidateSelector {

    private static final Logger log = LoggerFactory.getLogger(DriverCandidateSelector.class);

    private final LocationNearbyProvider locationNearbyProvider;
    private final DriverEligibilityProvider driverEligibilityProvider;
    private final DriverAvailabilityProvider driverAvailabilityProvider;
    private final DriverOfferRepository driverOfferRepository;
    private final CandidateRankingPolicy candidateRankingPolicy;
    private final Clock clock;
    private final MatchingProperties matchingProperties;

    public DriverCandidateSelector(
            LocationNearbyProvider locationNearbyProvider,
            DriverEligibilityProvider driverEligibilityProvider,
            DriverAvailabilityProvider driverAvailabilityProvider,
            DriverOfferRepository driverOfferRepository,
            CandidateRankingPolicy candidateRankingPolicy,
            Clock clock,
            MatchingProperties matchingProperties
    ) {
        this.locationNearbyProvider = Objects.requireNonNull(locationNearbyProvider, "locationNearbyProvider is required");
        this.driverEligibilityProvider = Objects.requireNonNull(driverEligibilityProvider, "driverEligibilityProvider is required");
        this.driverAvailabilityProvider = Objects.requireNonNull(driverAvailabilityProvider, "driverAvailabilityProvider is required");
        this.driverOfferRepository = Objects.requireNonNull(driverOfferRepository, "driverOfferRepository is required");
        this.candidateRankingPolicy = Objects.requireNonNull(candidateRankingPolicy, "candidateRankingPolicy is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.matchingProperties = Objects.requireNonNull(matchingProperties, "matchingProperties is required");
    }

    /**
     * Executes the complete candidate selection pipeline for a trip matching round.
     *
     * @param criteria trusted criteria snapshot derived from persisted Trip
     * @return ranked candidate selection result with diagnostic exclusion breakdown
     */
    public CandidateSelectionResult selectCandidates(MatchingCriteria criteria) {
        Objects.requireNonNull(criteria, "MatchingCriteria must not be null");

        Instant now = clock.instant();
        Map<UUID, CandidateExclusionReason> exclusions = new LinkedHashMap<>();

        log.debug("Starting candidate selection for trip={}, serviceType={}, vehicleType={}, radius={}km",
                criteria.tripId(), criteria.serviceType(), criteria.vehicleType(), criteria.radiusKm());

        // ---------------------------------------------------------------------
        // Step 1: Spatial Query via Location Service (Redis GEO)
        // ---------------------------------------------------------------------
        int spatialQueryLimit = Math.min(criteria.maxCandidates() * 4, 20);
        List<NearbyDriverLocation> nearbyLocations = locationNearbyProvider.findNearbyDrivers(
                criteria.pickupLongitude(),
                criteria.pickupLatitude(),
                criteria.radiusKm(),
                spatialQueryLimit
        );

        if (nearbyLocations == null || nearbyLocations.isEmpty()) {
            log.debug("Zero nearby drivers returned from Location Service for trip {}", criteria.tripId());
            return CandidateSelectionResult.empty(criteria.tripId(), criteria.searchRound(), criteria.radiusKm(), exclusions, now);
        }

        // Deduplicate driver IDs while preserving spatial proximity order
        Set<UUID> seenDriverIds = new LinkedHashSet<>();
        List<NearbyDriverLocation> uniqueLocations = new ArrayList<>();
        for (NearbyDriverLocation loc : nearbyLocations) {
            if (loc != null && loc.driverId() != null && seenDriverIds.add(loc.driverId())) {
                uniqueLocations.add(loc);
            }
        }

        // ---------------------------------------------------------------------
        // Step 2: GPS Freshness Validation (FR-MAT-005, FR-DRV-014)
        // ---------------------------------------------------------------------
        List<NearbyDriverLocation> freshLocations = new ArrayList<>();
        for (NearbyDriverLocation loc : uniqueLocations) {
            UUID driverId = loc.driverId();
            Instant gpsTimestamp = loc.lastSeenTimestamp();

            if (gpsTimestamp == null) {
                exclusions.put(driverId, CandidateExclusionReason.MISSING_GPS_TIMESTAMP);
                continue;
            }

            Duration age = Duration.between(gpsTimestamp, now);

            // Clock skew detection (timestamp in future beyond tolerance)
            if (age.isNegative() && Math.abs(age.getSeconds()) > matchingProperties.maxFutureSkewSeconds()) {
                exclusions.put(driverId, CandidateExclusionReason.GPS_IN_FUTURE);
                continue;
            }

            // Stale GPS detection (age exceeds 60s threshold per SRS)
            if (age.getSeconds() > matchingProperties.gpsMaxAgeSeconds()) {
                exclusions.put(driverId, CandidateExclusionReason.STALE_GPS);
                continue;
            }

            freshLocations.add(loc);
        }

        if (freshLocations.isEmpty()) {
            log.debug("All nearby drivers failed GPS freshness check for trip {}", criteria.tripId());
            return CandidateSelectionResult.empty(criteria.tripId(), criteria.searchRound(), criteria.radiusKm(), exclusions, now);
        }

        // ---------------------------------------------------------------------
        // Step 3: Local Dispatch State Check (FR-DRV-012, FR-MAT-011)
        // ---------------------------------------------------------------------
        List<NearbyDriverLocation> offerFreeLocations = new ArrayList<>();
        for (NearbyDriverLocation loc : freshLocations) {
            UUID driverId = loc.driverId();

            if (criteria.excludedDriverIds().contains(driverId)) {
                exclusions.put(driverId, CandidateExclusionReason.PREVIOUSLY_REJECTED);
                continue;
            }

            if (driverOfferRepository.existsByDriverIdAndStatus(driverId, OfferStatus.PENDING)) {
                exclusions.put(driverId, CandidateExclusionReason.HAS_PENDING_OFFER);
                continue;
            }

            if (driverOfferRepository.existsByDriverIdAndStatusAndAssignmentStatus(driverId, OfferStatus.ACCEPTED, AssignmentStatus.PENDING)) {
                exclusions.put(driverId, CandidateExclusionReason.ACCEPTED_PENDING_ASSIGNMENT);
                continue;
            }

            offerFreeLocations.add(loc);
        }

        if (offerFreeLocations.isEmpty()) {
            log.debug("All candidates excluded due to local offer state conflicts for trip {}", criteria.tripId());
            return CandidateSelectionResult.empty(criteria.tripId(), criteria.searchRound(), criteria.radiusKm(), exclusions, now);
        }

        // ---------------------------------------------------------------------
        // Step 4: External Operational Availability Check (FR-MAT-005, FR-TRIP-007)
        // ---------------------------------------------------------------------
        List<UUID> candidateDriverIds = offerFreeLocations.stream().map(NearbyDriverLocation::driverId).toList();
        Set<UUID> busyDriverIds = driverAvailabilityProvider.findBusyDrivers(candidateDriverIds);

        List<NearbyDriverLocation> availableLocations = new ArrayList<>();
        for (NearbyDriverLocation loc : offerFreeLocations) {
            UUID driverId = loc.driverId();
            if (busyDriverIds != null && busyDriverIds.contains(driverId)) {
                exclusions.put(driverId, CandidateExclusionReason.DRIVER_BUSY_IN_TRIP);
                continue;
            }
            availableLocations.add(loc);
        }

        if (availableLocations.isEmpty()) {
            log.debug("All candidates are currently busy with active trips for trip {}", criteria.tripId());
            return CandidateSelectionResult.empty(criteria.tripId(), criteria.searchRound(), criteria.radiusKm(), exclusions, now);
        }

        // ---------------------------------------------------------------------
        // Step 5: Driver Eligibility & Vehicle Compatibility Filter (FR-MAT-002, FR-DRV-008)
        // ---------------------------------------------------------------------
        List<UUID> availableIds = availableLocations.stream().map(NearbyDriverLocation::driverId).toList();
        Map<UUID, DriverEligibilityInfo> eligibilityMap = driverEligibilityProvider.getBatchEligibility(availableIds);

        List<DriverCandidate> qualifiedCandidates = new ArrayList<>();
        for (NearbyDriverLocation loc : availableLocations) {
            UUID driverId = loc.driverId();
            DriverEligibilityInfo eligibility = eligibilityMap != null ? eligibilityMap.get(driverId) : null;

            if (eligibility == null) {
                exclusions.put(driverId, CandidateExclusionReason.DRIVER_INELIGIBLE);
                continue;
            }

            if (eligibility.isSuspended()) {
                exclusions.put(driverId, CandidateExclusionReason.DRIVER_SUSPENDED);
                continue;
            }

            if (!eligibility.isAccountActive()) {
                exclusions.put(driverId, CandidateExclusionReason.ACCOUNT_INACTIVE);
                continue;
            }

            if (!eligibility.isApproved()) {
                exclusions.put(driverId, CandidateExclusionReason.PROFILE_NOT_APPROVED);
                continue;
            }

            if (!eligibility.hasDriverRole() || !eligibility.eligible()) {
                exclusions.put(driverId, CandidateExclusionReason.DRIVER_INELIGIBLE);
                continue;
            }

            // Vehicle compatibility & unambiguous vehicle selection check (Fix P1 Multi-Vehicle Safety)
            List<VehicleSummary> activeVehicles = eligibility.activeVehicles() != null
                    ? eligibility.activeVehicles().stream().filter(VehicleSummary::isActive).toList()
                    : List.of();

            if (activeVehicles.isEmpty()) {
                exclusions.put(driverId, CandidateExclusionReason.NO_ACTIVE_VEHICLE);
                continue;
            }

            UUID trustedVehicleId = loc.selectedVehicleId();
            UUID resolvedVehicleId;

            if (trustedVehicleId != null) {
                // If a trusted vehicle was specified, verify it belongs to driver's active vehicles
                Optional<VehicleSummary> matchedSelected = activeVehicles.stream()
                        .filter(v -> v.vehicleId().equals(trustedVehicleId))
                        .findFirst();

                if (matchedSelected.isEmpty()) {
                    exclusions.put(driverId, CandidateExclusionReason.NO_ACTIVE_VEHICLE);
                    continue;
                }

                if (matchedSelected.get().type() != criteria.vehicleType()) {
                    exclusions.put(driverId, CandidateExclusionReason.VEHICLE_TYPE_MISMATCH);
                    continue;
                }

                resolvedVehicleId = trustedVehicleId;
            } else {
                // No session vehicle specified
                // Check if driver has multiple active vehicles of different types
                long distinctVehicleTypes = activeVehicles.stream().map(VehicleSummary::type).distinct().count();
                if (distinctVehicleTypes > 1) {
                    exclusions.put(driverId, CandidateExclusionReason.AMBIGUOUS_ACTIVE_VEHICLES);
                    continue;
                }

                // Match required vehicle type
                List<VehicleSummary> matchingVehicles = activeVehicles.stream()
                        .filter(v -> v.type() == criteria.vehicleType())
                        .toList();

                if (matchingVehicles.isEmpty()) {
                    exclusions.put(driverId, CandidateExclusionReason.VEHICLE_TYPE_MISMATCH);
                    continue;
                }

                // If driver has multiple matching vehicles of the same type without session -> fail-closed (NEVER pick first!)
                if (matchingVehicles.size() > 1) {
                    exclusions.put(driverId, CandidateExclusionReason.AMBIGUOUS_ACTIVE_VEHICLES);
                    continue;
                }

                resolvedVehicleId = matchingVehicles.getFirst().vehicleId();
            }

            DriverCandidate candidate = DriverCandidate.builder()
                    .driverId(driverId)
                    .vehicleId(resolvedVehicleId)
                    .vehicleType(criteria.vehicleType())
                    .location(loc.longitude(), loc.latitude())
                    .distanceKm(loc.distanceKm())
                    .lastSeenTimestamp(loc.lastSeenTimestamp())
                    .build();

            qualifiedCandidates.add(candidate);
        }

        // ---------------------------------------------------------------------
        // Step 6: Candidate Ranking & Truncation (FR-MAT-003)
        // ---------------------------------------------------------------------
        List<DriverCandidate> ranked = candidateRankingPolicy.rank(qualifiedCandidates);
        if (ranked.size() > criteria.maxCandidates()) {
            ranked = ranked.subList(0, criteria.maxCandidates());
        }

        log.info("Candidate selection complete for trip {}: {} qualified candidates found out of {} nearby drivers",
                criteria.tripId(), ranked.size(), uniqueLocations.size());

        return new CandidateSelectionResult(
                criteria.tripId(),
                criteria.searchRound(),
                criteria.radiusKm(),
                ranked,
                exclusions,
                now
        );
    }
}
