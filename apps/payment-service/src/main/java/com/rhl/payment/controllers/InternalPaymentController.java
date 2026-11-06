package com.rhl.payment.controllers;

import com.rhl.payment.dtos.PaymentResponse;
import com.rhl.payment.services.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Machine-to-machine internal API for inter-service payment verification (/internal/**).
 * Secured by InternalAuthFilter (ROLE_INTERNAL_SERVICE).
 */
@RestController
@RequestMapping("/internal/v1/payments")
public class InternalPaymentController {

    private final PaymentService paymentService;

    public InternalPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentInternal(@PathVariable UUID paymentId) {
        PaymentResponse response = paymentService.getPaymentByIdInternal(paymentId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/trips/{tripId}")
    public ResponseEntity<List<PaymentResponse>> getTripPaymentsInternal(@PathVariable UUID tripId) {
        List<PaymentResponse> responses = paymentService.getPaymentsByTripIdInternal(tripId);
        return ResponseEntity.ok(responses);
    }
}
