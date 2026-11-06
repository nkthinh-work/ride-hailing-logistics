package com.rhl.payment.controllers;

import com.rhl.payment.dtos.PaymentResponse;
import com.rhl.payment.entities.PaymentReconciliationRecord;
import com.rhl.payment.enums.ReconciliationResolutionState;
import com.rhl.payment.repositories.PaymentReconciliationRepository;
import com.rhl.payment.services.PaymentReconciliationService;
import com.rhl.payment.services.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administrative REST API for payment reconciliation operations, mismatch inspection, and dispute resolution.
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
public class PaymentReconciliationController {

    private final PaymentReconciliationService reconciliationService;
    private final PaymentService paymentService;
    private final PaymentReconciliationRepository reconciliationRepository;

    public PaymentReconciliationController(
            PaymentReconciliationService reconciliationService,
            PaymentService paymentService,
            PaymentReconciliationRepository reconciliationRepository) {
        this.reconciliationService = reconciliationService;
        this.paymentService = paymentService;
        this.reconciliationRepository = reconciliationRepository;
    }

    /**
     * Manually triggers a batch reconciliation run across stuck / pending payment attempts.
     */
    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasRole('ADMINISTRATOR') or hasRole('FINANCE_STAFF')")
    public ResponseEntity<Map<String, Object>> runReconciliation() {
        int processed = reconciliationService.reconcileStalePendingAttempts();
        return ResponseEntity.ok(Map.of(
                "status", "COMPLETED",
                "processedAttempts", processed
        ));
    }

    /**
     * Lists unresolved financial reconciliation mismatches for auditing and manual resolution.
     */
    @GetMapping("/reconciliation/mismatches")
    @PreAuthorize("hasRole('ADMINISTRATOR') or hasRole('FINANCE_STAFF')")
    public ResponseEntity<List<PaymentReconciliationRecord>> getUnresolvedMismatches(
            @RequestParam(name = "state", defaultValue = "UNRESOLVED") ReconciliationResolutionState state) {
        List<PaymentReconciliationRecord> records = reconciliationRepository.findByResolutionStateOrderByCheckedAtDesc(state);
        return ResponseEntity.ok(records);
    }

    /**
     * Reconciles a single specific payment attempt by attempt ID.
     */
    @PostMapping("/attempts/{attemptId}/reconcile")
    @PreAuthorize("hasRole('ADMINISTRATOR') or hasRole('FINANCE_STAFF')")
    public ResponseEntity<PaymentResponse> reconcileAttempt(@PathVariable UUID attemptId) {
        PaymentResponse response = paymentService.reconcileAttempt(attemptId);
        return ResponseEntity.ok(response);
    }
}
