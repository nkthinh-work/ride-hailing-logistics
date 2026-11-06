package com.rhl.payment.controllers;

import com.rhl.payment.dtos.RefundResponse;
import com.rhl.payment.services.PaymentRefundService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Finance Staff REST API for refund approval, reconciliation, and audit review (SRS Section 4.8.4).
 * Enforces role-based access control (hasRole('FINANCE_STAFF') or hasRole('ADMINISTRATOR')).
 */
@RestController
@RequestMapping("/api/v1/finance/refunds")
public class FinanceRefundController {

    private final PaymentRefundService refundService;

    public FinanceRefundController(PaymentRefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * Approves and executes an eligible refund request through the payment gateway provider.
     */
    @PostMapping("/{refundId}/approve")
    @PreAuthorize("hasRole('FINANCE_STAFF') or hasRole('ADMINISTRATOR')")
    public ResponseEntity<RefundResponse> approveRefund(
            @PathVariable UUID refundId,
            @RequestParam(name = "note", defaultValue = "Approved by finance staff") String note,
            @AuthenticationPrincipal Jwt jwt) {

        UUID staffId = extractStaffId(jwt);
        RefundResponse response = refundService.approveAndExecuteRefund(staffId, refundId, note);
        return ResponseEntity.ok(response);
    }

    /**
     * Reconciles an in-flight or unknown refund attempt directly against provider refund query API.
     */
    @PostMapping("/{refundId}/reconcile")
    @PreAuthorize("hasRole('FINANCE_STAFF') or hasRole('ADMINISTRATOR')")
    public ResponseEntity<RefundResponse> reconcileRefund(@PathVariable UUID refundId) {
        RefundResponse response = refundService.reconcileRefund(refundId);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves complete audit record and status history for a refund.
     */
    @GetMapping("/{refundId}")
    @PreAuthorize("hasRole('FINANCE_STAFF') or hasRole('ADMINISTRATOR')")
    public ResponseEntity<RefundResponse> getRefundById(
            @PathVariable UUID refundId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID staffId = extractStaffId(jwt);
        RefundResponse response = refundService.getRefundById(staffId, refundId, true);
        return ResponseEntity.ok(response);
    }

    private UUID extractStaffId(Jwt jwt) {
        if (jwt.hasClaim("userId") && jwt.getClaimAsString("userId") != null) {
            return UUID.fromString(jwt.getClaimAsString("userId"));
        }
        return UUID.fromString(jwt.getSubject());
    }
}
