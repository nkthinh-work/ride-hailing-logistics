package com.rhl.pricing.controllers;

import com.rhl.pricing.dtos.FareQuoteRequest;
import com.rhl.pricing.dtos.FareQuoteResponse;
import com.rhl.pricing.entities.FareQuote;
import com.rhl.pricing.services.FareQuoteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.UUID;

/**
 * Controller exposing minimal vertical slice for Fare Quotes (FR-PRI-013..015).
 * Only POST (create quote) and GET by quoteId are exposed.
 * No CRUD endpoints, no price editing endpoints.
 */
@RestController
@RequestMapping("/api/v1/fares")
public class FareQuoteController {

    private final FareQuoteService fareQuoteService;
    private final Clock clock;

    public FareQuoteController(FareQuoteService fareQuoteService, Clock clock) {
        this.fareQuoteService = fareQuoteService;
        this.clock = clock;
    }

    /**
     * Issues an immutable Fare Quote for the authenticated customer.
     */
    @PostMapping("/quote")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<FareQuoteResponse> createQuote(
            @Valid @RequestBody FareQuoteRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = UUID.fromString(jwt.getSubject());
        FareQuote quote = fareQuoteService.createQuote(request, customerId);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(FareQuoteResponse.from(quote, clock.instant()));
    }

    /**
     * Retrieves an issued quote by quoteId, verifying Customer Ownership.
     */
    @GetMapping("/quotes/{quoteId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<FareQuoteResponse> getQuote(
            @PathVariable UUID quoteId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = UUID.fromString(jwt.getSubject());
        FareQuote quote = fareQuoteService.getQuoteById(quoteId, customerId);
        return ResponseEntity.ok(FareQuoteResponse.from(quote, clock.instant()));
    }
}
