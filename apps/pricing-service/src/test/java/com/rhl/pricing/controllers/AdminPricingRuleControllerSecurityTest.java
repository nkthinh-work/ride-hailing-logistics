package com.rhl.pricing.controllers;

import com.rhl.pricing.configs.SecurityConfig;
import com.rhl.pricing.dtos.CreatePricingRuleRequest;
import com.rhl.pricing.entities.PricingRule;
import com.rhl.pricing.entities.PricingRuleAudit;
import com.rhl.pricing.exceptions.GlobalExceptionHandler;
import com.rhl.pricing.exceptions.PricingRuleConflictException;
import com.rhl.pricing.exceptions.PricingRuleNotFoundException;
import com.rhl.pricing.models.PricingAuditAction;
import com.rhl.pricing.models.PricingRuleStatus;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import com.rhl.pricing.services.PricingRuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminPricingRuleController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminPricingRuleControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PricingRuleService pricingRuleService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID adminId = UUID.randomUUID();
    private final String validCreateJson = """
            {
                "serviceType": "RIDE",
                "vehicleType": "MOTORBIKE",
                "regionId": "DEFAULT",
                "baseFare": 12000,
                "includedDistanceKm": 2.0,
                "pricePerKm": 4500,
                "pricePerMinute": 400,
                "minimumFare": 12000,
                "currency": "VND",
                "effectiveFrom": "2026-11-01T00:00:00Z"
            }
            """;

    @Nested
    @DisplayName("Authentication & Authorization Tests")
    class AuthTests {

        @Test
        @DisplayName("401 Unauthorized when request lacks JWT")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/admin/pricing-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validCreateJson))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 Forbidden when user is CUSTOMER without pricing authority")
        void customerRole_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/admin/pricing-rules")
                            .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validCreateJson))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("403 Forbidden when user is DRIVER without pricing authority")
        void driverRole_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/admin/pricing-rules")
                            .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_DRIVER"),
                                            new SimpleGrantedAuthority("LOCATION_UPDATE")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validCreateJson))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("201 Created when user has ROLE_ADMINISTRATOR")
        void administratorRole_returns201() throws Exception {
            PricingRule created = new PricingRule(
                    UUID.randomUUID(), ServiceType.RIDE, VehicleType.MOTORBIKE, "DEFAULT", 1,
                    new BigDecimal("12000"), new BigDecimal("2.0"), new BigDecimal("4500"),
                    new BigDecimal("400"), new BigDecimal("12000"), "VND",
                    PricingRuleStatus.PUBLISHED, Instant.parse("2026-11-01T00:00:00Z"), null,
                    Instant.now(), adminId
            );

            when(pricingRuleService.createAndPublishRule(any(CreatePricingRuleRequest.class), eq(adminId)))
                    .thenReturn(created);

            mockMvc.perform(post("/api/v1/admin/pricing-rules")
                            .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validCreateJson))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.serviceType").value("RIDE"))
                    .andExpect(jsonPath("$.vehicleType").value("MOTORBIKE"))
                    .andExpect(jsonPath("$.status").value("PUBLISHED"));
        }

        @Test
        @DisplayName("201 Created when user has PRICING_MANAGE authority directly")
        void pricingManageAuthority_returns201() throws Exception {
            PricingRule created = new PricingRule(
                    UUID.randomUUID(), ServiceType.RIDE, VehicleType.MOTORBIKE, "DEFAULT", 1,
                    new BigDecimal("12000"), new BigDecimal("2.0"), new BigDecimal("4500"),
                    new BigDecimal("400"), new BigDecimal("12000"), "VND",
                    PricingRuleStatus.PUBLISHED, Instant.parse("2026-11-01T00:00:00Z"), null,
                    Instant.now(), adminId
            );

            when(pricingRuleService.createAndPublishRule(any(CreatePricingRuleRequest.class), eq(adminId)))
                    .thenReturn(created);

            mockMvc.perform(post("/api/v1/admin/pricing-rules")
                            .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                    .authorities(new SimpleGrantedAuthority("PRICING_MANAGE")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validCreateJson))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("Error Handling & Status Mapping Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("409 Conflict when rule overlaps with existing published rule")
        void conflictingRule_returns409() throws Exception {
            when(pricingRuleService.createAndPublishRule(any(), any()))
                    .thenThrow(new PricingRuleConflictException("Pricing rule conflict detected with existing rule v1"));

            mockMvc.perform(post("/api/v1/admin/pricing-rules")
                            .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validCreateJson))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRICING_RULE_CONFLICT"))
                    .andExpect(jsonPath("$.message").value("Pricing rule conflict detected with existing rule v1"));
        }

        @Test
        @DisplayName("400 Bad Request when request body has negative baseFare")
        void negativeBaseFare_returns400() throws Exception {
            String invalidJson = """
                    {
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE",
                        "baseFare": -5000,
                        "pricePerKm": 4500,
                        "pricePerMinute": 400,
                        "minimumFare": 12000,
                        "effectiveFrom": "2026-11-01T00:00:00Z"
                    }
                    """;

            mockMvc.perform(post("/api/v1/admin/pricing-rules")
                            .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("404 Not Found when rule does not exist")
        void ruleNotFound_returns404() throws Exception {
            UUID randomId = UUID.randomUUID();
            when(pricingRuleService.getRuleById(randomId))
                    .thenThrow(new PricingRuleNotFoundException("Pricing rule not found: " + randomId));

            mockMvc.perform(get("/api/v1/admin/pricing-rules/" + randomId)
                            .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRICING_RULE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("Audit Trail & Lifecycle Endpoint Tests")
    class AuditEndpointTests {

        @Test
        @DisplayName("GET /{id}/audits returns audit history list")
        void getAudits_returnsList() throws Exception {
            UUID ruleId = UUID.randomUUID();
            PricingRuleAudit audit = new PricingRuleAudit(
                    UUID.randomUUID(), ruleId, 1, PricingAuditAction.RULE_PUBLISHED, adminId,
                    "Initial publication", "{}", Instant.now()
            );

            when(pricingRuleService.getRuleAudits(ruleId)).thenReturn(List.of(audit));

            mockMvc.perform(get("/api/v1/admin/pricing-rules/" + ruleId + "/audits")
                            .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].ruleId").value(ruleId.toString()))
                    .andExpect(jsonPath("$[0].action").value("RULE_PUBLISHED"))
                    .andExpect(jsonPath("$[0].version").value(1));
        }

        @Test
        @DisplayName("PATCH /{id}/publish as ADMINISTRATOR returns 200 OK")
        void publishRule_asAdmin_returns200() throws Exception {
            UUID ruleId = UUID.randomUUID();
            PricingRule published = new PricingRule(
                    ruleId, ServiceType.RIDE, VehicleType.MOTORBIKE, "DEFAULT", 1,
                    new BigDecimal("12000"), new BigDecimal("2.0"), new BigDecimal("4500"),
                    new BigDecimal("400"), new BigDecimal("12000"), "VND",
                    PricingRuleStatus.PUBLISHED, Instant.now(), null, Instant.now(), adminId
            );

            when(pricingRuleService.publishDraftRule(eq(ruleId), any(), eq(adminId)))
                    .thenReturn(published);

            mockMvc.perform(patch("/api/v1/admin/pricing-rules/" + ruleId + "/publish")
                            .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("PATCH /{id}/publish as CUSTOMER returns 403 Forbidden")
        void publishRule_asCustomer_returns403() throws Exception {
            UUID ruleId = UUID.randomUUID();

            mockMvc.perform(patch("/api/v1/admin/pricing-rules/" + ruleId + "/publish")
                            .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }
}
