package com.rhl.payment.controllers;

import com.rhl.payment.services.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public narrow Webhook endpoint for external payment provider IPN callbacks (SRS Section 4.8.1, FR-PAY-004..006).
 * Protected cryptographically via HMAC-SHA256 signature verification (P0 Security).
 */
@RestController
@RequestMapping("/api/v1/payments/webhook")
public class PaymentWebhookController {

    private final PaymentService paymentService;

    public PaymentWebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Ingests and authenticates provider callback (e.g. MoMo IPN).
     */
    @PostMapping("/{provider}")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @PathVariable String provider,
            @RequestBody String rawPayload,
            @RequestHeader Map<String, String> headers) {

        Map<String, Object> result = paymentService.processProviderWebhook(provider, rawPayload, headers);
        return ResponseEntity.ok(result);
    }
}
