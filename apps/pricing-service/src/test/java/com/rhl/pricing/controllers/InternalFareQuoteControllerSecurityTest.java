package com.rhl.pricing.controllers;

import com.rhl.pricing.configs.SecurityConfig;
import com.rhl.pricing.entities.FareQuote;
import com.rhl.pricing.exceptions.GlobalExceptionHandler;
import com.rhl.pricing.exceptions.QuoteAccessDeniedException;
import com.rhl.pricing.exceptions.QuoteExpiredException;
import com.rhl.pricing.exceptions.QuoteNotFoundException;
import com.rhl.pricing.models.FareBreakdown;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import com.rhl.pricing.services.FareQuoteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalFareQuoteController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("InternalFareQuoteController Security & Contract Tests")
class InternalFareQuoteControllerSecurityTest {

    private static final String VALID_INTERNAL_SECRET = "rhl-pricing-internal-secret-2026";
    private static final String VALIDATE_URL = "/internal/v1/fares/quotes/validate";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FareQuoteService fareQuoteService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID quoteId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    private FareQuote createMockQuote(UUID qId, UUID cId) {
        Instant now = Instant.now();
        FareBreakdown breakdown = new FareBreakdown(
                new BigDecimal("12000.00"),
                new BigDecimal("2.00"),
                new BigDecimal("3.00"),
                new BigDecimal("13500.00"),
                new BigDecimal("10.00"),
                new BigDecimal("4000.00"),
                new BigDecimal("29500.00"),
                new BigDecimal("12000.00"),
                new BigDecimal("29500.00"),
                new BigDecimal("1.25"),
                new BigDecimal("36875.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("3887.50"),
                BigDecimal.ZERO,
                new BigDecimal("42762.50"),
                "VND",
                "DEV-v1.0"
        );

        return new FareQuote(
                qId,
                cId,
                ServiceType.RIDE,
                VehicleType.MOTORBIKE,
                "DEFAULT",
                new BigDecimal("10.7769"),
                new BigDecimal("106.7009"),
                "Saigon Opera House",
                new BigDecimal("10.7725"),
                new BigDecimal("106.6980"),
                "Ben Thanh Market",
                5000L,
                600L,
                "SIMULATED_TEST",
                true,
                "RULE-001",
                "DEV-v1.0",
                breakdown,
                now,
                now.plusSeconds(300)
        );
    }

    @Nested
    @DisplayName("Machine-to-Machine Authentication Tests")
    class MachineAuthenticationTests {

        @Test
        @DisplayName("Missing X-Internal-Secret header should return 401 Unauthorized")
        void missingSecret_Returns401() throws Exception {
            String requestBody = """
                    {
                        "quoteId": "%s",
                        "customerId": "%s",
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE",
                        "customerConfirmedSurge": true
                    }
                    """.formatted(quoteId, customerId);

            mockMvc.perform(post(VALIDATE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
        }

        @Test
        @DisplayName("Invalid X-Internal-Secret header should return 401 Unauthorized")
        void invalidSecret_Returns401() throws Exception {
            String requestBody = """
                    {
                        "quoteId": "%s",
                        "customerId": "%s",
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE",
                        "customerConfirmedSurge": true
                    }
                    """.formatted(quoteId, customerId);

            mockMvc.perform(post(VALIDATE_URL)
                            .header("X-Internal-Secret", "wrong-secret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
        }
    }

    @Nested
    @DisplayName("Quote Validation Business Logic Tests")
    class BusinessLogicTests {

        @Test
        @DisplayName("Valid internal request with valid quote returns 200 and trusted snapshot")
        void validQuote_Returns200AndSnapshot() throws Exception {
            FareQuote mockQuote = createMockQuote(quoteId, customerId);
            when(fareQuoteService.validateQuoteForTrip(
                    eq(quoteId), eq(customerId), eq(ServiceType.RIDE), eq(VehicleType.MOTORBIKE), eq(true)
            )).thenReturn(mockQuote);

            String requestBody = """
                    {
                        "quoteId": "%s",
                        "customerId": "%s",
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE",
                        "customerConfirmedSurge": true
                    }
                    """.formatted(quoteId, customerId);

            mockMvc.perform(post(VALIDATE_URL)
                            .header("X-Internal-Secret", VALID_INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
                    .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                    .andExpect(jsonPath("$.serviceType").value("RIDE"))
                    .andExpect(jsonPath("$.vehicleType").value("MOTORBIKE"))
                    .andExpect(jsonPath("$.estimatedFare").value(42762.50))
                    .andExpect(jsonPath("$.surgeMultiplier").value(1.25))
                    .andExpect(jsonPath("$.currency").value("VND"))
                    .andExpect(jsonPath("$.pricingRuleVersion").value("DEV-v1.0"));
        }

        @Test
        @DisplayName("Expired quote returns 410 Gone")
        void expiredQuote_Returns410() throws Exception {
            when(fareQuoteService.validateQuoteForTrip(any(), any(), any(), any(), anyBoolean()))
                    .thenThrow(new QuoteExpiredException("Quote has expired"));

            String requestBody = """
                    {
                        "quoteId": "%s",
                        "customerId": "%s",
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE",
                        "customerConfirmedSurge": true
                    }
                    """.formatted(quoteId, customerId);

            mockMvc.perform(post(VALIDATE_URL)
                            .header("X-Internal-Secret", VALID_INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
        }

        @Test
        @DisplayName("Quote belonging to another customer returns 403 Forbidden")
        void wrongOwner_Returns403() throws Exception {
            when(fareQuoteService.validateQuoteForTrip(any(), any(), any(), any(), anyBoolean()))
                    .thenThrow(new QuoteAccessDeniedException("Customer does not own this quote"));

            String requestBody = """
                    {
                        "quoteId": "%s",
                        "customerId": "%s",
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE",
                        "customerConfirmedSurge": true
                    }
                    """.formatted(quoteId, customerId);

            mockMvc.perform(post(VALIDATE_URL)
                            .header("X-Internal-Secret", VALID_INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("Quote not found returns 404 Not Found")
        void notFound_Returns404() throws Exception {
            when(fareQuoteService.validateQuoteForTrip(any(), any(), any(), any(), anyBoolean()))
                    .thenThrow(new QuoteNotFoundException("Quote not found"));

            String requestBody = """
                    {
                        "quoteId": "%s",
                        "customerId": "%s",
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE",
                        "customerConfirmedSurge": true
                    }
                    """.formatted(quoteId, customerId);

            mockMvc.perform(post(VALIDATE_URL)
                            .header("X-Internal-Secret", VALID_INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
        }

        @Test
        @DisplayName("Surge unconfirmed when surge applied returns 400 Bad Request")
        void surgeUnconfirmed_Returns400() throws Exception {
            when(fareQuoteService.validateQuoteForTrip(any(), any(), any(), any(), eq(false)))
                    .thenThrow(new IllegalArgumentException("Customer must explicitly confirm surge multiplier 1.25"));

            String requestBody = """
                    {
                        "quoteId": "%s",
                        "customerId": "%s",
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE",
                        "customerConfirmedSurge": false
                    }
                    """.formatted(quoteId, customerId);

            mockMvc.perform(post(VALIDATE_URL)
                            .header("X-Internal-Secret", VALID_INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
    }
}
