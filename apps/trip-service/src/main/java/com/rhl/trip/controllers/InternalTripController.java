package com.rhl.trip.controllers;

import com.rhl.trip.dtos.BatchDriverBusyRequest;
import com.rhl.trip.dtos.DriverBusyResponse;
import com.rhl.trip.dtos.TripMatchingCriteriaResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
import com.rhl.trip.exceptions.TripNotFoundException;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/trips")
public class InternalTripController {

    private static final Set<TripStatus> BUSY_STATUSES = Set.of(
            TripStatus.ACCEPTED,
            TripStatus.PICKING_UP,
            TripStatus.ARRIVED,
            TripStatus.IN_TRIP
    );

    private final TripRepository tripRepository;
    private final com.rhl.trip.services.DispatchTripAssignmentService assignmentService;

    public InternalTripController(TripRepository tripRepository,
                                  com.rhl.trip.services.DispatchTripAssignmentService assignmentService) {
        this.tripRepository = tripRepository;
        this.assignmentService = assignmentService;
    }

    @GetMapping("/drivers/{driverId}/busy")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<DriverBusyResponse> isDriverBusy(@PathVariable UUID driverId) {
        boolean busy = tripRepository.existsByDriverIdAndStatusIn(driverId, BUSY_STATUSES);
        return ResponseEntity.ok(new DriverBusyResponse(driverId, busy));
    }

    @GetMapping("/drivers/{driverId}/active-trip")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<com.rhl.trip.dtos.TripResponse> getActiveTripByDriver(@PathVariable UUID driverId) {
        return tripRepository.findFirstByDriverIdAndStatusIn(driverId, BUSY_STATUSES)
                .map(trip -> ResponseEntity.ok(com.rhl.trip.dtos.TripResponse.from(trip)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/drivers/busy")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<List<UUID>> findBusyDrivers(@Valid @RequestBody BatchDriverBusyRequest request) {
        if (request == null || request.driverIds() == null || request.driverIds().isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        List<UUID> busyDriverIds = tripRepository.findBusyDriverIds(request.driverIds(), BUSY_STATUSES);
        return ResponseEntity.ok(busyDriverIds);
    }

    @GetMapping("/{tripId}")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<com.rhl.trip.dtos.TripResponse> getTripById(@PathVariable UUID tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));
        return ResponseEntity.ok(com.rhl.trip.dtos.TripResponse.from(trip));
    }

    @GetMapping("/{tripId}/matching-criteria")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<TripMatchingCriteriaResponse> getMatchingCriteria(@PathVariable UUID tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        if (trip.getStatus() != TripStatus.MATCHING) {
            throw new InvalidTripStateTransitionException(
                    trip.getStatus(),
                    TripStatus.MATCHING,
                    "Trip " + tripId + " is not in MATCHING status (current: " + trip.getStatus() + ")"
            );
        }

        TripMatchingCriteriaResponse response = new TripMatchingCriteriaResponse(
                trip.getId(),
                trip.getCustomerId(),
                trip.getStatus(),
                trip.getPickupLatitude(),
                trip.getPickupLongitude(),
                trip.getServiceType() != null ? trip.getServiceType().name() : null,
                trip.getVehicleType()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{tripId}/assign-driver")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<com.rhl.trip.dtos.TripResponse> assignDriver(
            @PathVariable UUID tripId,
            @Valid @RequestBody com.rhl.trip.dtos.AssignDriverRequest request) {

        String actorRole = request.actorRole() != null ? request.actorRole() : "DRIVER";
        com.rhl.trip.dtos.TripResponse response = assignmentService.assignDriver(
                tripId,
                request.driverId(),
                request.vehicleId(),
                actorRole
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{tripId}/start-matching")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<com.rhl.trip.dtos.TripResponse> startMatching(@PathVariable UUID tripId) {
        com.rhl.trip.dtos.TripResponse response = assignmentService.startMatching(tripId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{tripId}/no-driver")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<com.rhl.trip.dtos.TripResponse> markNoDriver(
            @PathVariable UUID tripId,
            @RequestBody(required = false) com.rhl.trip.dtos.NoDriverRequest request) {

        String reason = request != null && request.reason() != null ? request.reason() : "Matching limits reached with no driver available";
        com.rhl.trip.dtos.TripResponse response = assignmentService.markNoDriver(tripId, reason);
        return ResponseEntity.ok(response);
    }
}

