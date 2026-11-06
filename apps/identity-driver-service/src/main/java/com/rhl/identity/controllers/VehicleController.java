package com.rhl.identity.controllers;

import com.rhl.identity.dtos.VehicleRequest;
import com.rhl.identity.dtos.VehicleResponse;
import com.rhl.identity.services.interfaces.IVehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/drivers/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final IVehicleService vehicleService;

    @PostMapping
    public ResponseEntity<VehicleResponse> registerVehicle(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VehicleRequest request) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(vehicleService.registerVehicle(userId, request));
    }

    @GetMapping("/me")
    public ResponseEntity<List<VehicleResponse>> getMyVehicles(
            @AuthenticationPrincipal Jwt jwt) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                vehicleService.getMyVehicles(userId)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<VehicleResponse> getVehicleById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                vehicleService.getVehicleById(userId, id)
        );
    }

    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<List<VehicleResponse>> getVehiclesByApplication(
            @PathVariable UUID applicationId) {

        return ResponseEntity.ok(
                vehicleService.getVehiclesByDriverProfileId(applicationId)
        );
    }
}
