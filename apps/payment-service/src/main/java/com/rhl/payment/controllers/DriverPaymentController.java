package com.rhl.payment.controllers;

import com.rhl.payment.dtos.PaymentResponse;
import com.rhl.payment.services.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Driver REST API for cash collection confirmations (SRS Section 4.8.1, FR-PAY-007).
 * Strictly guarded to require ROLE_DRIVER and driver ownership of the completed trip.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class DriverPaymentController {

    private final PaymentService paymentService;

    public DriverPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Assigned driver retrieves payment transactions for a completed trip assigned to them.
     */
    @GetMapping("/driver/trips/{tripId}")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<List<PaymentResponse>> getDriverTripPayments(
            @PathVariable UUID tripId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = extractDriverId(jwt);
        List<PaymentResponse> responses = paymentService.getPaymentsForDriverTrip(driverId, tripId);
        return ResponseEntity.ok(responses);
    }

    /**
     * Assigned driver confirms physical cash received from passenger for completed trip.
     */
    @PostMapping("/{paymentId}/cash/confirm")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<PaymentResponse> confirmCashPayment(
            @PathVariable UUID paymentId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID driverId = extractDriverId(jwt);
        PaymentResponse response = paymentService.confirmCashPayment(driverId, paymentId);
        return ResponseEntity.ok(response);
    }

    private UUID extractDriverId(Jwt jwt) {
        if (jwt.hasClaim("userId") && jwt.getClaimAsString("userId") != null) {
            return UUID.fromString(jwt.getClaimAsString("userId"));
        }
        return UUID.fromString(jwt.getSubject());
    }
}
