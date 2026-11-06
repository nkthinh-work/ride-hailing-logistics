
package com.rhl.identity.controllers;

import com.rhl.identity.dtos.*;
import com.rhl.identity.entities.enums.DriverApplicationStatus;

import com.rhl.identity.services.interfaces.IDriverService;
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
@RequestMapping("/api/v1/drivers/applications")
@RequiredArgsConstructor
public class DriverController {

    private final IDriverService driverService;

    @PostMapping
    public ResponseEntity<DriverApplicationResponse> apply(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DriverApplicationRequest request) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(driverService.apply(userId, request));
    }

    @GetMapping("/me")
    public ResponseEntity<DriverApplicationResponse> getMine(
            @AuthenticationPrincipal Jwt jwt) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                driverService.getMyApplication(userId)
        );
    }

    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @GetMapping
    public ResponseEntity<List<DriverApplicationResponse>> list(
            @RequestParam(defaultValue = "PENDING")
            DriverApplicationStatus status) {

        return ResponseEntity.ok(
                driverService.listApplications(status)
        );
    }

    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @PatchMapping("/{id}/approve")
    public ResponseEntity<DriverApplicationResponse> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {

        UUID reviewerId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                driverService.approve(reviewerId, id)
        );
    }

    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @PatchMapping("/{id}/reject")
    public ResponseEntity<DriverApplicationResponse> reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody RejectDriverRequest request) {

        UUID reviewerId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                driverService.reject(reviewerId, id, request)
        );
    }

    @GetMapping("/me/eligibility")
    public ResponseEntity<DriverEligibilityResponse> getMyEligibility(
            @AuthenticationPrincipal Jwt jwt) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                driverService.getEligibilityByUserId(userId)
        );
    }

    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @GetMapping("/{id}/eligibility")
    public ResponseEntity<DriverEligibilityResponse> getApplicationEligibility(
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                driverService.getEligibilityByApplicationId(id)
        );
    }

    @PreAuthorize("hasAuthority('DRIVER_SUSPEND') or hasRole('ADMINISTRATOR')")
    @PatchMapping("/{id}/suspend")
    public ResponseEntity<DriverApplicationResponse> suspend(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody SuspendDriverRequest request) {

        UUID actorId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                driverService.suspend(actorId, id, request)
        );
    }

    @PreAuthorize("hasAuthority('DRIVER_SUSPEND') or hasRole('ADMINISTRATOR')")
    @PatchMapping("/{id}/reactivate")
    public ResponseEntity<DriverApplicationResponse> reactivate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {

        UUID actorId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                driverService.reactivate(actorId, id)
        );
    }

    @PreAuthorize("hasAuthority('DRIVER_REVIEW') or hasAuthority('DRIVER_SUSPEND') or hasRole('ADMINISTRATOR')")
    @GetMapping("/{id}/audit-logs")
    public ResponseEntity<List<DriverAuditLogResponse>> getAuditLogs(
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                driverService.getAuditLogs(id)
        );
    }
}