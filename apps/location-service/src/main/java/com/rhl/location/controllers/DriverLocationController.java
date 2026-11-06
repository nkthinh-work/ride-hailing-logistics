
package com.rhl.location.controllers;

import com.rhl.location.dtos.*;
import com.rhl.location.services.DriverLocationService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/locations")
public class DriverLocationController {

    private final DriverLocationService locationService;

    public DriverLocationController(
            DriverLocationService locationService) {
        this.locationService = locationService;
    }

    @PostMapping("/me/online")
    @PreAuthorize("hasRole('DRIVER') and hasAuthority('LOCATION_UPDATE')")
    public ResponseEntity<DriverLocationResponse> online(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody LocationUpdateRequest request) {

        UUID driverId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                locationService.online(driverId, request)
        );
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('DRIVER') and hasAuthority('LOCATION_UPDATE')")
    public ResponseEntity<DriverLocationResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody LocationUpdateRequest request) {

        UUID driverId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                locationService.update(driverId, request)
        );
    }

    @PostMapping("/me/offline")
    @PreAuthorize("hasRole('DRIVER') and hasAuthority('LOCATION_UPDATE')")
    public ResponseEntity<DriverLocationResponse> offline(
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                locationService.offline(driverId)
        );
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<DriverLocationResponse> getMine(
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                locationService.getMine(driverId)
        );
    }

    @GetMapping("/nearby")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ResponseEntity<List<NearbyDriverResponse>> nearby(
            @RequestParam double longitude,
            @RequestParam double latitude,
            @RequestParam(defaultValue = "3") double radiusKm,
            @RequestParam(defaultValue = "10") int limit) {

        return ResponseEntity.ok(
                locationService.nearby(
                        longitude,
                        latitude,
                        radiusKm,
                        limit
                )
        );
    }
}
