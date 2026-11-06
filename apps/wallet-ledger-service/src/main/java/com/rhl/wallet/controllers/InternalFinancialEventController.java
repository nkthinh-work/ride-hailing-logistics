package com.rhl.wallet.controllers;

import com.rhl.wallet.dtos.FinancialEventIngestRequest;
import com.rhl.wallet.dtos.FinancialEventIngestResponse;
import com.rhl.wallet.services.FinancialEventIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine-to-machine internal financial event ingestion endpoint (CON-07, FR-EVT-001..008).
 * Strictly secured by InternalAuthFilter requiring ROLE_INTERNAL_SERVICE and constant-time secret check.
 */
@RestController
@RequestMapping("/internal/v1/financial-events")
public class InternalFinancialEventController {

    private final FinancialEventIngestionService ingestionService;

    public InternalFinancialEventController(FinancialEventIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<FinancialEventIngestResponse> ingestEvent(@RequestBody FinancialEventIngestRequest request) {
        FinancialEventIngestResponse response = ingestionService.ingestEvent(request);
        return ResponseEntity.ok(response);
    }
}
