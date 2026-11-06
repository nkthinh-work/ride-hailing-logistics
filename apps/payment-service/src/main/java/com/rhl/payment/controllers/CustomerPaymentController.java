package com.rhl.payment.controllers;

import com.rhl.payment.dtos.InitiatePaymentRequest;
import com.rhl.payment.dtos.PaymentResponse;
import com.rhl.payment.services.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Customer REST API for payment initiation and queries (SRS Section 4.8.1, FR-PAY-001..003).
 */
@RestController
@RequestMapping("/api/v1/payments")
public class CustomerPaymentController {

    private final PaymentService paymentService;

    public CustomerPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Initiates a payment obligation for an authorized trip.
     */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<PaymentResponse> initiatePayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InitiatePaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = extractCustomerId(jwt);
        PaymentResponse response = paymentService.initiatePayment(customerId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieves all payment transactions owned by the authenticated customer.
     */
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<List<PaymentResponse>> getMyPayments(
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = extractCustomerId(jwt);
        List<PaymentResponse> responses = paymentService.getMyPayments(customerId);
        return ResponseEntity.ok(responses);
    }

    /**
     * Retrieves an individual payment transaction owned by the customer.
     */
    @GetMapping("/{paymentId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable UUID paymentId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = extractCustomerId(jwt);
        PaymentResponse response = paymentService.getPaymentById(customerId, paymentId);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves payment transactions for a trip owned by the customer.
     */
    @GetMapping("/trips/{tripId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<List<PaymentResponse>> getTripPayments(
            @PathVariable UUID tripId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = extractCustomerId(jwt);
        List<PaymentResponse> responses = paymentService.getPaymentsByTripId(customerId, tripId);
        return ResponseEntity.ok(responses);
    }

    private UUID extractCustomerId(Jwt jwt) {
        if (jwt.hasClaim("userId") && jwt.getClaimAsString("userId") != null) {
            return UUID.fromString(jwt.getClaimAsString("userId"));
        }
        return UUID.fromString(jwt.getSubject());
    }
}
