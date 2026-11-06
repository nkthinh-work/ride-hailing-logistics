package com.rhl.trip.controllers;

import com.rhl.trip.clients.LocationServiceClient;
import com.rhl.trip.dtos.TripLocationSnapshotResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.TripNotFoundException;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * REST controller for authoritative Trip and Driver Location Snapshot synchronization (FR-RT-008, NFR-SEC-004).
 * Supports authoritative reconnect state reconciliation without exposing internal driver metadata.
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripLocationController {

    private static final Set<TripStatus> STREAMABLE_STATUSES = Set.of(
            TripStatus.ACCEPTED,
            TripStatus.PICKING_UP,
            TripStatus.ARRIVED,
            TripStatus.IN_TRIP
    );

    private final TripRepository tripRepository;
    private final LocationServiceClient locationServiceClient;

    public TripLocationController(
            TripRepository tripRepository,
            LocationServiceClient locationServiceClient
    ) {
        this.tripRepository = Objects.requireNonNull(tripRepository, "tripRepository is required");
        this.locationServiceClient = Objects.requireNonNull(locationServiceClient, "locationServiceClient is required");
    }

    @GetMapping("/{tripId}/location")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER', 'SUPPORT_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<TripLocationSnapshotResponse> getTripLocation(
            @PathVariable UUID tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        UUID callerId = UUID.fromString(jwt.getSubject());
        boolean isStaffOrAdmin = isStaffOrAdmin(jwt);

        boolean isCustomer = callerId.equals(trip.getCustomerId());
        boolean isAssignedDriver = trip.getDriverId() != null && callerId.equals(trip.getDriverId());

        if (!isStaffOrAdmin && !isCustomer && !isAssignedDriver) {
            throw new AccessDeniedException("Access Denied: You are not authorized to view location for trip " + tripId);
        }

        if (!STREAMABLE_STATUSES.contains(trip.getStatus()) || trip.getDriverId() == null) {
            return ResponseEntity.ok(new TripLocationSnapshotResponse(
                    trip.getId(),
                    trip.getDriverId(),
                    trip.getStatus().name(),
                    null,
                    null,
                    null,
                    false
            ));
        }

        Optional<LocationServiceClient.DriverLocationSnapshot> locOpt =
                locationServiceClient.getDriverLocation(trip.getDriverId());

        if (locOpt.isEmpty() || locOpt.get().latitude() == null || locOpt.get().longitude() == null) {
            return ResponseEntity.ok(new TripLocationSnapshotResponse(
                    trip.getId(),
                    trip.getDriverId(),
                    trip.getStatus().name(),
                    null,
                    null,
                    null,
                    false
            ));
        }

        LocationServiceClient.DriverLocationSnapshot loc = locOpt.get();
        return ResponseEntity.ok(new TripLocationSnapshotResponse(
                trip.getId(),
                trip.getDriverId(),
                trip.getStatus().name(),
                loc.latitude(),
                loc.longitude(),
                loc.recordedAt(),
                true
        ));
    }

    private boolean isStaffOrAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            return roles.contains("ADMINISTRATOR") || roles.contains("SUPPORT_STAFF");
        }
        return false;
    }
}
