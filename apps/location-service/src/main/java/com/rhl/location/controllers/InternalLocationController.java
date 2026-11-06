package com.rhl.location.controllers;

import com.rhl.location.dtos.NearbyDriverResponse;
import com.rhl.location.services.DriverLocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/v1/locations")
public class InternalLocationController {

    private final DriverLocationService locationService;

    public InternalLocationController(DriverLocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/nearby")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
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

    @GetMapping("/drivers/{driverId}")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<com.rhl.location.dtos.DriverLocationResponse> getDriverLocation(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID driverId) {
        com.rhl.location.dtos.DriverLocationResponse response = locationService.getMine(driverId);
        if (response == null || "OFFLINE".equals(response.status()) || response.latitude() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }
}
