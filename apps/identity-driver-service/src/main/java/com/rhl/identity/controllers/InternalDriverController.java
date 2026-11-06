package com.rhl.identity.controllers;

import com.rhl.identity.dtos.BatchDriverEligibilityRequest;
import com.rhl.identity.dtos.BatchDriverVehiclesRequest;
import com.rhl.identity.dtos.DriverEligibilityResponse;
import com.rhl.identity.dtos.InternalVehicleResponse;
import com.rhl.identity.entities.Vehicle;
import com.rhl.identity.repositories.VehicleRepository;
import com.rhl.identity.services.interfaces.IDriverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/drivers")
@RequiredArgsConstructor
public class InternalDriverController {

    private final IDriverService driverService;
    private final VehicleRepository vehicleRepository;

    @GetMapping("/{userId}/eligibility")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<DriverEligibilityResponse> getDriverEligibility(
            @PathVariable UUID userId) {

        return ResponseEntity.ok(
                driverService.getEligibilityByUserId(userId)
        );
    }

    @PostMapping("/eligibility/batch")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<List<DriverEligibilityResponse>> getBatchEligibility(
            @RequestBody BatchDriverEligibilityRequest request) {

        List<UUID> userIds = request != null ? request.userIds() : List.of();
        return ResponseEntity.ok(
                driverService.getBatchEligibility(userIds)
        );
    }

    @GetMapping("/{userId}/vehicles")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<List<InternalVehicleResponse>> getDriverVehicles(
            @PathVariable UUID userId) {

        List<Vehicle> vehicles = vehicleRepository.findByUserId(userId);
        List<InternalVehicleResponse> dtos = vehicles.stream()
                .map(v -> new InternalVehicleResponse(
                        v.getId(),
                        v.getDriverProfile() != null ? v.getDriverProfile().getId() : null,
                        v.getType() != null ? v.getType().name() : null,
                        v.getPlate(),
                        v.getMake(),
                        v.getModel(),
                        v.getColor(),
                        v.getStatus() != null ? v.getStatus().name() : null
                ))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/vehicles/batch")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<List<InternalVehicleResponse>> getBatchDriverVehicles(
            @Valid @RequestBody BatchDriverVehiclesRequest request) {

        if (request == null || request.userIds() == null || request.userIds().isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        List<Vehicle> vehicles = vehicleRepository.findByUserIds(request.userIds());
        List<InternalVehicleResponse> dtos = vehicles.stream()
                .map(v -> new InternalVehicleResponse(
                        v.getId(),
                        v.getDriverProfile() != null ? v.getDriverProfile().getId() : null,
                        v.getType() != null ? v.getType().name() : null,
                        v.getPlate(),
                        v.getMake(),
                        v.getModel(),
                        v.getColor(),
                        v.getStatus() != null ? v.getStatus().name() : null
                ))
                .toList();

        return ResponseEntity.ok(dtos);
    }
}
