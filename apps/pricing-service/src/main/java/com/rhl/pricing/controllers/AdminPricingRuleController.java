package com.rhl.pricing.controllers;

import com.rhl.pricing.dtos.CreatePricingRuleRequest;
import com.rhl.pricing.dtos.PricingRuleAuditResponse;
import com.rhl.pricing.dtos.PricingRuleResponse;
import com.rhl.pricing.entities.PricingRule;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import com.rhl.pricing.services.PricingRuleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/pricing-rules")
@PreAuthorize("hasRole('ADMINISTRATOR') or hasAuthority('PRICING_MANAGE')")
public class AdminPricingRuleController {

    private final PricingRuleService pricingRuleService;

    public AdminPricingRuleController(PricingRuleService pricingRuleService) {
        this.pricingRuleService = pricingRuleService;
    }

    @PostMapping
    public ResponseEntity<PricingRuleResponse> createAndPublishRule(
            @Valid @RequestBody CreatePricingRuleRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID actorId = UUID.fromString(jwt.getSubject());
        PricingRule created = pricingRuleService.createAndPublishRule(request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(PricingRuleResponse.fromEntity(created));
    }

    @GetMapping
    public ResponseEntity<List<PricingRuleResponse>> listRules(
            @RequestParam(required = false) ServiceType serviceType,
            @RequestParam(required = false) VehicleType vehicleType,
            @RequestParam(required = false) String regionId) {

        List<PricingRuleResponse> list = pricingRuleService.listRules(serviceType, vehicleType, regionId)
                .stream()
                .map(PricingRuleResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PricingRuleResponse> getRuleById(@PathVariable UUID id) {
        PricingRule rule = pricingRuleService.getRuleById(id);
        return ResponseEntity.ok(PricingRuleResponse.fromEntity(rule));
    }

    @GetMapping("/{id}/audits")
    public ResponseEntity<List<PricingRuleAuditResponse>> getRuleAudits(@PathVariable UUID id) {
        List<PricingRuleAuditResponse> audits = pricingRuleService.getRuleAudits(id)
                .stream()
                .map(PricingRuleAuditResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(audits);
    }

    @PatchMapping("/{id}/publish")
    public ResponseEntity<PricingRuleResponse> publishRule(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal Jwt jwt) {

        UUID actorId = UUID.fromString(jwt.getSubject());
        PricingRule published = pricingRuleService.publishDraftRule(id, reason, actorId);
        return ResponseEntity.ok(PricingRuleResponse.fromEntity(published));
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<PricingRuleResponse> archiveRule(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal Jwt jwt) {

        UUID actorId = UUID.fromString(jwt.getSubject());
        PricingRule archived = pricingRuleService.archiveRule(id, reason, actorId);
        return ResponseEntity.ok(PricingRuleResponse.fromEntity(archived));
    }
}
