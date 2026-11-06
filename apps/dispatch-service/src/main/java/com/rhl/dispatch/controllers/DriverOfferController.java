package com.rhl.dispatch.controllers;

import com.rhl.dispatch.dtos.DriverOfferResponse;
import com.rhl.dispatch.dtos.RejectOfferRequest;
import com.rhl.dispatch.services.DriverOfferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * REST Controller for Driver Offer actions (M06 Group 1, FR-MAT-009..012).
 * Endpoints:
 * - POST /api/v1/offers/{offerId}/accept (Driver only)
 * - POST /api/v1/offers/{offerId}/reject (Driver only)
 * - GET /api/v1/offers/pending (Driver only)
 * - GET /api/v1/offers/{offerId} (Driver only)
 */
@RestController
@RequestMapping("/api/v1/offers")
public class DriverOfferController {

    private final DriverOfferService driverOfferService;
    private final com.rhl.dispatch.services.MatchingOrchestrator matchingOrchestrator;

    public DriverOfferController(
            DriverOfferService driverOfferService,
            com.rhl.dispatch.services.MatchingOrchestrator matchingOrchestrator) {
        this.driverOfferService = driverOfferService;
        this.matchingOrchestrator = matchingOrchestrator;
    }

    /**
     * Driver accepts an offer (FR-MAT-009, FR-MAT-010).
     */
    @PostMapping("/{offerId}/accept")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<DriverOfferResponse> acceptOffer(
            @PathVariable UUID offerId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());
        matchingOrchestrator.acceptOffer(offerId, driverId);
        DriverOfferResponse response = driverOfferService.getOfferById(offerId);
        return ResponseEntity.ok(response);
    }

    /**
     * Driver rejects an offer (FR-MAT-009).
     */
    @PostMapping("/{offerId}/reject")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<DriverOfferResponse> rejectOffer(
            @PathVariable UUID offerId,
            @Valid @RequestBody(required = false) RejectOfferRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());
        String reason = request != null ? request.reason() : null;
        matchingOrchestrator.rejectOffer(offerId, driverId, reason);
        DriverOfferResponse response = driverOfferService.getOfferById(offerId);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves active pending offer for the authenticated driver.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<DriverOfferResponse> getPendingOffer(
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());
        Optional<DriverOfferResponse> pending = driverOfferService.getPendingOfferForDriver(driverId);
        return ResponseEntity.of(pending);
    }

    /**
     * Retrieves offer details by ID.
     */
    @GetMapping("/{offerId}")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<DriverOfferResponse> getOffer(
            @PathVariable UUID offerId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = UUID.fromString(jwt.getSubject());
        DriverOfferResponse offer = driverOfferService.getOfferById(offerId);

        // Ownership guard: driver can only inspect their own offer
        if (!offer.driverId().equals(driverId)) {
            return ResponseEntity.status(403).build();
        }

        return ResponseEntity.ok(offer);
    }
}
