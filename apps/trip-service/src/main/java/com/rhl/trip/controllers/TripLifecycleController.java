package com.rhl.trip.controllers;

import com.rhl.trip.dtos.CancelTripRequest;
import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.services.TripCancellationService;
import com.rhl.trip.services.TripProgressionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for Trip Cancellation and Driver Lifecycle Progression (M05 Group 3).
 * Endpoints:
 * - POST /api/v1/trips/{tripId}/cancel (Customer, Driver, SupportStaff, Admin)
 * - POST /api/v1/trips/{tripId}/start-pickup (Driver)
 * - POST /api/v1/trips/{tripId}/arrive (Driver)
 * - POST /api/v1/trips/{tripId}/start (Driver)
 * - POST /api/v1/trips/{tripId}/complete (Driver)
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripLifecycleController {

    private final TripCancellationService tripCancellationService;
    private final TripProgressionService tripProgressionService;

    public TripLifecycleController(
            TripCancellationService tripCancellationService,
            TripProgressionService tripProgressionService) {
        this.tripCancellationService = tripCancellationService;
        this.tripProgressionService = tripProgressionService;
    }

    /**
     * Cancels an active trip (FR-CAN-001..007).
     */
    @PostMapping("/{tripId}/cancel")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER', 'SUPPORT_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<TripResponse> cancelTrip(
            @PathVariable UUID tripId,
            @Valid @RequestBody CancelTripRequest request,
            @AuthenticationPrincipal Jwt jwt,
            org.springframework.security.core.Authentication authentication) {

        UUID actorId = UUID.fromString(jwt.getSubject());
        String actorRole = extractActorRole(jwt, authentication);

        TripResponse response = tripCancellationService.cancelTrip(tripId, actorId, actorRole, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Driver starts heading to pickup location (ACCEPTED -> PICKING_UP).
     */
    @PostMapping("/{tripId}/start-pickup")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<TripResponse> startPickingUp(
            @PathVariable UUID tripId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());
        TripResponse response = tripProgressionService.startPickingUp(tripId, driverId);
        return ResponseEntity.ok(response);
    }

    /**
     * Driver confirms arrival at pickup location (PICKING_UP -> ARRIVED).
     */
    @PostMapping("/{tripId}/arrive")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<TripResponse> confirmArrived(
            @PathVariable UUID tripId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());
        TripResponse response = tripProgressionService.confirmArrived(tripId, driverId);
        return ResponseEntity.ok(response);
    }

    /**
     * Passenger on board; driver starts the journey (ARRIVED -> IN_TRIP).
     */
    @PostMapping("/{tripId}/start")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<TripResponse> startTrip(
            @PathVariable UUID tripId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());
        TripResponse response = tripProgressionService.startTrip(tripId, driverId);
        return ResponseEntity.ok(response);
    }

    /**
     * Destination reached; driver completes the trip (IN_TRIP -> COMPLETED).
     */
    @PostMapping("/{tripId}/complete")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<TripResponse> completeTrip(
            @PathVariable UUID tripId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());
        TripResponse response = tripProgressionService.completeTrip(tripId, driverId);
        return ResponseEntity.ok(response);
    }

    private String extractActorRole(Jwt jwt, org.springframework.security.core.Authentication authentication) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            if (roles.contains("ADMINISTRATOR")) return "ADMINISTRATOR";
            if (roles.contains("SUPPORT_STAFF")) return "SUPPORT_STAFF";
            if (roles.contains("DRIVER")) return "DRIVER";
            if (roles.contains("CUSTOMER")) return "CUSTOMER";
            if (!roles.isEmpty()) return roles.get(0);
        }
        if (authentication != null && authentication.getAuthorities() != null) {
            for (org.springframework.security.core.GrantedAuthority ga : authentication.getAuthorities()) {
                if ("ROLE_ADMINISTRATOR".equals(ga.getAuthority())) return "ADMINISTRATOR";
                if ("ROLE_SUPPORT_STAFF".equals(ga.getAuthority())) return "SUPPORT_STAFF";
                if ("ROLE_DRIVER".equals(ga.getAuthority())) return "DRIVER";
                if ("ROLE_CUSTOMER".equals(ga.getAuthority())) return "CUSTOMER";
            }
        }
        return "CUSTOMER";
    }
}
