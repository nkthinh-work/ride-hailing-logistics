package com.rhl.dispatch.controllers;

import com.rhl.dispatch.configs.MatchingProperties;
import com.rhl.dispatch.dtos.CandidateSelectionResponse;
import com.rhl.dispatch.dtos.MatchingCriteriaRequest;
import com.rhl.dispatch.exceptions.TripValidationException;
import com.rhl.dispatch.models.CandidateSelectionResult;
import com.rhl.dispatch.models.MatchingCriteria;
import com.rhl.dispatch.models.ServiceType;
import com.rhl.dispatch.models.TripMatchingCriteriaSnapshot;
import com.rhl.dispatch.models.VehicleType;
import com.rhl.dispatch.providers.TripCriteriaProvider;
import com.rhl.dispatch.services.DriverCandidateSelector;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/**
 * Internal machine-to-machine endpoint for Driver Candidate Selection (FR-MAT-001..006).
 * Protected by InternalAuthFilter requiring ROLE_INTERNAL_SERVICE and X-Internal-Secret.
 * Validates authoritative Trip state and criteria from Trip Service before matching.
 */
@RestController
@RequestMapping("/internal/v1/dispatch/matching")
public class InternalMatchingController {

    private final DriverCandidateSelector candidateSelector;
    private final MatchingProperties matchingProperties;
    private final TripCriteriaProvider tripCriteriaProvider;
    private final com.rhl.dispatch.services.MatchingOrchestrator matchingOrchestrator;

    public InternalMatchingController(
            DriverCandidateSelector candidateSelector,
            MatchingProperties matchingProperties,
            TripCriteriaProvider tripCriteriaProvider,
            com.rhl.dispatch.services.MatchingOrchestrator matchingOrchestrator
    ) {
        this.candidateSelector = Objects.requireNonNull(candidateSelector, "candidateSelector is required");
        this.matchingProperties = Objects.requireNonNull(matchingProperties, "matchingProperties is required");
        this.tripCriteriaProvider = Objects.requireNonNull(tripCriteriaProvider, "tripCriteriaProvider is required");
        this.matchingOrchestrator = Objects.requireNonNull(matchingOrchestrator, "matchingOrchestrator is required");
    }

    @PostMapping("/start")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<com.rhl.dispatch.models.MatchingOrchestrationResult> startMatching(
            @Valid @RequestBody com.rhl.dispatch.dtos.StartMatchingRequest request) {
        com.rhl.dispatch.models.MatchingOrchestrationResult result =
                matchingOrchestrator.startOrContinueMatching(request.tripId());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/cancel")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<com.rhl.dispatch.models.MatchingOrchestrationResult> cancelMatching(
            @Valid @RequestBody com.rhl.dispatch.dtos.CancelMatchingRequest request) {
        com.rhl.dispatch.models.MatchingOrchestrationResult result =
                matchingOrchestrator.cancelMatching(request.tripId(), request.reason());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/candidates")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<CandidateSelectionResponse> findCandidates(
            @Valid @RequestBody MatchingCriteriaRequest request) {

        // 1. Authoritative check: Trip must exist and be in MATCHING status
        TripMatchingCriteriaSnapshot snapshot = tripCriteriaProvider.getTripMatchingCriteria(request.tripId());

        if (!"MATCHING".equalsIgnoreCase(snapshot.status())) {
            throw new TripValidationException(
                    409,
                    "TRIP_NOT_MATCHING",
                    "Trip " + request.tripId() + " is not in MATCHING status: " + snapshot.status()
            );
        }

        // 2. Cross-verify request fields against authoritative persisted Trip snapshot
        if (request.pickupLatitude() != null && snapshot.pickupLatitude() != null) {
            if (Math.abs(request.pickupLatitude() - snapshot.pickupLatitude()) > 0.0001) {
                throw new TripValidationException(
                        400,
                        "CRITERIA_MISMATCH",
                        "Requested pickup latitude does not match authoritative Trip record"
                );
            }
        }

        if (request.pickupLongitude() != null && snapshot.pickupLongitude() != null) {
            if (Math.abs(request.pickupLongitude() - snapshot.pickupLongitude()) > 0.0001) {
                throw new TripValidationException(
                        400,
                        "CRITERIA_MISMATCH",
                        "Requested pickup longitude does not match authoritative Trip record"
                );
            }
        }

        if (request.serviceType() != null && snapshot.serviceType() != null) {
            if (!request.serviceType().name().equalsIgnoreCase(snapshot.serviceType())) {
                throw new TripValidationException(
                        400,
                        "CRITERIA_MISMATCH",
                        "Requested service type does not match authoritative Trip record"
                );
            }
        }

        if (request.vehicleType() != null && snapshot.vehicleType() != null) {
            if (!request.vehicleType().name().equalsIgnoreCase(snapshot.vehicleType())) {
                throw new TripValidationException(
                        400,
                        "CRITERIA_MISMATCH",
                        "Requested vehicle type does not match authoritative Trip record"
                );
            }
        }

        // 3. Build criteria using authoritative values
        double defaultRadius = !matchingProperties.radiiKm().isEmpty() ? matchingProperties.radiiKm().getFirst() : 1.0;
        MatchingCriteria criteria = MatchingCriteria.builder()
                .tripId(snapshot.tripId())
                .pickupLocation(snapshot.pickupLatitude(), snapshot.pickupLongitude())
                .serviceType(ServiceType.valueOf(snapshot.serviceType().toUpperCase()))
                .vehicleType(VehicleType.valueOf(snapshot.vehicleType().toUpperCase()))
                .radiusKm(request.radiusKm() != null ? request.radiusKm() : defaultRadius)
                .maxCandidates(request.maxCandidates() != null ? request.maxCandidates() : matchingProperties.maxCandidates())
                .searchRound(request.searchRound() != null ? request.searchRound() : 1)
                .excludedDriverIds(request.excludedDriverIds() != null ? request.excludedDriverIds() : Set.of())
                .build();

        CandidateSelectionResult result = candidateSelector.selectCandidates(criteria);
        return ResponseEntity.ok(CandidateSelectionResponse.from(result));
    }
}
