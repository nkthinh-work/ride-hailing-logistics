package com.rhl.pricing.controllers;

import com.rhl.pricing.dtos.QuoteValidationRequest;
import com.rhl.pricing.dtos.QuoteValidationResponse;
import com.rhl.pricing.entities.FareQuote;
import com.rhl.pricing.services.FareQuoteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal machine-to-machine endpoint for cross-service quote validation (FR-PRI-015, DR-004).
 * Protected by InternalAuthFilter requiring ROLE_INTERNAL_SERVICE and X-Internal-Secret.
 */
@RestController
@RequestMapping("/internal/v1/fares")
public class InternalFareQuoteController {

    private final FareQuoteService fareQuoteService;

    public InternalFareQuoteController(FareQuoteService fareQuoteService) {
        this.fareQuoteService = fareQuoteService;
    }

    @PostMapping("/quotes/validate")
    public ResponseEntity<QuoteValidationResponse> validateQuote(
            @Valid @RequestBody QuoteValidationRequest request) {

        FareQuote quote = fareQuoteService.validateQuoteForTrip(
                request.quoteId(),
                request.customerId(),
                request.serviceType(),
                request.vehicleType(),
                request.customerConfirmedSurge()
        );

        return ResponseEntity.ok(QuoteValidationResponse.from(quote));
    }
}
