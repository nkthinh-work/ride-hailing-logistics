package com.rhl.payment.controllers;

import com.rhl.payment.dtos.CreateRefundRequest;
import com.rhl.payment.dtos.RefundEligibilityResponse;
import com.rhl.payment.dtos.RefundResponse;
import com.rhl.payment.services.PaymentRefundService;
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
 * Customer REST API for refund requests, status queries, and eligibility checks.
 * Invariants: Original payment must be SUCCEEDED, no over-refund, policy gating.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class CustomerRefundController {

    private final PaymentRefundService refundService;

    public CustomerRefundController(PaymentRefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * Customer requests a refund for a successful payment.
     */
    @PostMapping("/{paymentId}/refunds")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<RefundResponse> requestRefund(
            @PathVariable UUID paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = extractCustomerId(jwt);
        RefundResponse response = refundService.requestRefund(customerId, paymentId, idempotencyKey, request, false);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Checks whether a payment is eligible for refund and retrieves maximum refundable balance.
     */
    @GetMapping("/{paymentId}/refunds/eligibility")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<RefundEligibilityResponse> checkEligibility(
            @PathVariable UUID paymentId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = extractCustomerId(jwt);
        RefundEligibilityResponse response = refundService.checkRefundEligibility(customerId, paymentId, false);
        return ResponseEntity.ok(response);
    }

    /**
     * Lists all refunds associated with a payment for the authenticated customer.
     */
    @GetMapping("/{paymentId}/refunds")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<List<RefundResponse>> getRefundsForPayment(
            @PathVariable UUID paymentId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = extractCustomerId(jwt);
        List<RefundResponse> responses = refundService.getRefundsByPaymentId(customerId, paymentId, false);
        return ResponseEntity.ok(responses);
    }

    /**
     * Retrieves specific refund details by refund ID for the authenticated customer.
     */
    @GetMapping("/refunds/{refundId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<RefundResponse> getRefundById(
            @PathVariable UUID refundId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = extractCustomerId(jwt);
        RefundResponse response = refundService.getRefundById(customerId, refundId, false);
        return ResponseEntity.ok(response);
    }

    private UUID extractCustomerId(Jwt jwt) {
        if (jwt.hasClaim("userId") && jwt.getClaimAsString("userId") != null) {
            return UUID.fromString(jwt.getClaimAsString("userId"));
        }
        return UUID.fromString(jwt.getSubject());
    }
}
