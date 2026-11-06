package com.rhl.pricing.controllers;

import com.rhl.pricing.configs.PricingConfig;
import com.rhl.pricing.configs.SecurityConfig;
import com.rhl.pricing.dtos.FareQuoteRequest;
import com.rhl.pricing.entities.FareQuote;
import com.rhl.pricing.exceptions.GlobalExceptionHandler;
import com.rhl.pricing.exceptions.ProductionQuotingBlockedException;
import com.rhl.pricing.exceptions.QuoteAccessDeniedException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FareQuoteController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, PricingConfig.class})
@DisplayName("FareQuoteController Security & Contract Tests")
class FareQuoteControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FareQuoteService fareQuoteService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID customerId = UUID.randomUUID();

    private final String validQuoteRequestJson = """
            {
                "serviceType": "RIDE",
                "vehicleType": "MOTORBIKE",
                "pickupLatitude": 10.7769,
                "pickupLongitude": 106.7009,
                "pickupAddress": "Saigon Opera House, District 1",
                "dropoffLatitude": 10.7725,
                "dropoffLongitude": 106.6980,
                "dropoffAddress": "Ben Thanh Market, District 1",
                "regionId": "DEFAULT"
            }
            """;

    private FareQuote createMockQuote(UUID quoteId, UUID ownerId) {
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
                new BigDecimal("1.0000"),
                new BigDecimal("29500.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("29500"),
                "VND",
                "v1"
        );

        return new FareQuote(
                quoteId,
                ownerId,
                ServiceType.RIDE,
                VehicleType.MOTORBIKE,
                "DEFAULT",
                new BigDecimal("10.7769"),
                new BigDecimal("106.7009"),
                "Saigon Opera House, District 1",
                new BigDecimal("10.7725"),
                new BigDecimal("106.6980"),
                "Ben Thanh Market, District 1",
                5000L,
                600L,
                "SIMULATED_DEV_FIXTURE",
                true,
                "RULE-001",
                "v1",
                breakdown,
                now,
                now.plusSeconds(300)
        );
    }

    @Nested
    @DisplayName("POST /api/v1/fares/quote Tests")
    class CreateQuoteTests {

        @Test
        @DisplayName("Authenticated customer should create quote successfully (201 Created)")
        void shouldCreateQuoteWhenAuthenticated() throws Exception {
            UUID quoteId = UUID.randomUUID();
            FareQuote mockQuote = createMockQuote(quoteId, customerId);

            when(fareQuoteService.createQuote(any(FareQuoteRequest.class), eq(customerId)))
                    .thenReturn(mockQuote);

            mockMvc.perform(post("/api/v1/fares/quote")
                            .with(jwt().jwt(j -> j.subject(customerId.toString())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validQuoteRequestJson))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
                    .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                    .andExpect(jsonPath("$.serviceType").value("RIDE"))
                    .andExpect(jsonPath("$.vehicleType").value("MOTORBIKE"))
                    .andExpect(jsonPath("$.distanceMeters").value(5000))
                    .andExpect(jsonPath("$.durationSeconds").value(600))
                    .andExpect(jsonPath("$.totalFare").value(29500))
                    .andExpect(jsonPath("$.currency").value("VND"))
                    .andExpect(jsonPath("$.isExpired").value(false));
        }

        @Test
        @DisplayName("Unauthenticated request should return 401 Unauthorized")
        void shouldReturn401WhenUnauthenticated() throws Exception {
            mockMvc.perform(post("/api/v1/fares/quote")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validQuoteRequestJson))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Invalid latitude should return 400 Bad Request (VALIDATION_ERROR)")
        void shouldReturn400OnInvalidLatitude() throws Exception {
            String invalidJson = """
                    {
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE",
                        "pickupLatitude": 95.0,
                        "pickupLongitude": 106.7009,
                        "dropoffLatitude": 10.7725,
                        "dropoffLongitude": 106.6980
                    }
                    """;

            mockMvc.perform(post("/api/v1/fares/quote")
                            .with(jwt().jwt(j -> j.subject(customerId.toString())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("Missing serviceType should return 400 Bad Request (VALIDATION_ERROR)")
        void shouldReturn400OnMissingServiceType() throws Exception {
            String invalidJson = """
                    {
                        "vehicleType": "MOTORBIKE",
                        "pickupLatitude": 10.7769,
                        "pickupLongitude": 106.7009,
                        "dropoffLatitude": 10.7725,
                        "dropoffLongitude": 106.6980
                    }
                    """;

            mockMvc.perform(post("/api/v1/fares/quote")
                            .with(jwt().jwt(j -> j.subject(customerId.toString())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("Production quoting blocked exception should return 422 Unprocessable Content")
        void shouldReturn422WhenProductionQuotingBlocked() throws Exception {
            when(fareQuoteService.createQuote(any(FareQuoteRequest.class), eq(customerId)))
                    .thenThrow(new ProductionQuotingBlockedException("Simulated routing blocked in production"));

            mockMvc.perform(post("/api/v1/fares/quote")
                            .with(jwt().jwt(j -> j.subject(customerId.toString())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validQuoteRequestJson))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.code").value("PRODUCTION_QUOTING_BLOCKED"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/fares/quotes/{quoteId} Tests")
    class GetQuoteTests {

        @Test
        @DisplayName("Owner customer should successfully retrieve quote (200 OK)")
        void shouldReturn200ForOwner() throws Exception {
            UUID quoteId = UUID.randomUUID();
            FareQuote mockQuote = createMockQuote(quoteId, customerId);

            when(fareQuoteService.getQuoteById(quoteId, customerId)).thenReturn(mockQuote);

            mockMvc.perform(get("/api/v1/fares/quotes/" + quoteId)
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
                    .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                    .andExpect(jsonPath("$.totalFare").value(29500));
        }

        @Test
        @DisplayName("Non-owner customer accessing quote should return 403 Forbidden (ACCESS_DENIED)")
        void shouldReturn403ForNonOwner() throws Exception {
            UUID quoteId = UUID.randomUUID();
            UUID strangerId = UUID.randomUUID();

            when(fareQuoteService.getQuoteById(quoteId, strangerId))
                    .thenThrow(new QuoteAccessDeniedException("Customer does not own fare quote: " + quoteId));

            mockMvc.perform(get("/api/v1/fares/quotes/" + quoteId)
                            .with(jwt().jwt(j -> j.subject(strangerId.toString()))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("Non-existent quote should return 404 Not Found (QUOTE_NOT_FOUND)")
        void shouldReturn404WhenQuoteNotFound() throws Exception {
            UUID quoteId = UUID.randomUUID();

            when(fareQuoteService.getQuoteById(quoteId, customerId))
                    .thenThrow(new QuoteNotFoundException("Fare quote not found: " + quoteId));

            mockMvc.perform(get("/api/v1/fares/quotes/" + quoteId)
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
        }

        @Test
        @DisplayName("Unauthenticated request should return 401 Unauthorized")
        void shouldReturn401WhenUnauthenticated() throws Exception {
            UUID quoteId = UUID.randomUUID();

            mockMvc.perform(get("/api/v1/fares/quotes/" + quoteId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
