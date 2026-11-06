package com.rhl.trip.controllers;

import com.rhl.trip.configs.SecurityConfig;
import com.rhl.trip.dtos.CreateTripRequest;
import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.exceptions.ActiveTripConflictException;
import com.rhl.trip.exceptions.GlobalExceptionHandler;
import com.rhl.trip.exceptions.IdempotencyConflictException;
import com.rhl.trip.exceptions.PricingServiceUnavailableException;
import com.rhl.trip.exceptions.QuoteExpiredException;
import com.rhl.trip.exceptions.QuoteNotFoundException;
import com.rhl.trip.exceptions.QuoteReplayConflictException;
import com.rhl.trip.exceptions.TripAccessDeniedException;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.services.BookingService;
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

@WebMvcTest(controllers = TripBookingController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("TripBookingController Security & HTTP Tests")
class TripBookingControllerSecurityTest {

    private static final String BASE_URL = "/api/v1/trips";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID customerId = UUID.randomUUID();
    private final UUID quoteId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    private final String validRequestJson = """
            {
                "quoteId": "%s",
                "idempotencyKey": "key-abc-123",
                "serviceType": "RIDE",
                "vehicleType": "MOTORBIKE",
                "customerConfirmedSurge": true
            }
            """.formatted(quoteId);

    private TripResponse createMockResponse() {
        return new TripResponse(
                tripId,
                customerId,
                null,
                TripStatus.CREATED,
                quoteId,
                "key-abc-123",
                ServiceType.RIDE,
                "MOTORBIKE",
                new BigDecimal("10.7769"),
                new BigDecimal("106.7009"),
                "Saigon Opera House",
                new BigDecimal("10.7725"),
                new BigDecimal("106.6980"),
                "Ben Thanh Market",
                new BigDecimal("5.00"),
                10,
                new BigDecimal("42762.50"),
                new BigDecimal("1.25"),
                "VND",
                "DEV-v1.0",
                Instant.now(),
                null, null, null, null, null,
                0L
        );
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor asCustomer(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor asDriver(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    @Nested
    @DisplayName("Authentication & Role Authorization")
    class AuthorizationTests {

        @Test
        @DisplayName("Unauthenticated request to POST /api/v1/trips returns 401 Unauthorized")
        void unauthenticated_Returns401() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("User with ROLE_DRIVER cannot call booking endpoint - returns 403 Forbidden")
        void driverRole_Returns403() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .with(asDriver(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("User with ROLE_CUSTOMER can call booking endpoint - returns 201 Created")
        void customerRole_Returns201() throws Exception {
            when(bookingService.bookTrip(eq(customerId), any(CreateTripRequest.class)))
                    .thenReturn(createMockResponse());

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                    .andExpect(jsonPath("$.status").value("CREATED"))
                    .andExpect(jsonPath("$.estimatedFare").value(42762.50));
        }
    }

    @Nested
    @DisplayName("Error Contract Mapping Tests")
    class ErrorContractTests {

        @Test
        @DisplayName("Active trip conflict returns 409 Conflict")
        void activeTripConflict_Returns409() throws Exception {
            when(bookingService.bookTrip(eq(customerId), any()))
                    .thenThrow(new ActiveTripConflictException(customerId));

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ACTIVE_TRIP_CONFLICT"));
        }

        @Test
        @DisplayName("Quote replay conflict returns 409 Conflict")
        void quoteReplayConflict_Returns409() throws Exception {
            when(bookingService.bookTrip(eq(customerId), any()))
                    .thenThrow(new QuoteReplayConflictException(quoteId));

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("QUOTE_REPLAY_CONFLICT"));
        }

        @Test
        @DisplayName("Idempotency conflict returns 409 Conflict")
        void idempotencyConflict_Returns409() throws Exception {
            when(bookingService.bookTrip(eq(customerId), any()))
                    .thenThrow(new IdempotencyConflictException("key-abc-123"));

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        }

        @Test
        @DisplayName("Quote expired returns 422 Unprocessable Content")
        void quoteExpired_Returns422() throws Exception {
            when(bookingService.bookTrip(eq(customerId), any()))
                    .thenThrow(new QuoteExpiredException(quoteId));

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
        }

        @Test
        @DisplayName("Quote not found returns 404 Not Found")
        void quoteNotFound_Returns404() throws Exception {
            when(bookingService.bookTrip(eq(customerId), any()))
                    .thenThrow(new QuoteNotFoundException(quoteId));

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
        }

        @Test
        @DisplayName("Pricing service unavailable returns 503 Service Unavailable")
        void pricingUnavailable_Returns503() throws Exception {
            when(bookingService.bookTrip(eq(customerId), any()))
                    .thenThrow(new PricingServiceUnavailableException("Connection refused"));

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PRICING_SERVICE_UNAVAILABLE"));
        }
    }

    @Nested
    @DisplayName("Query Trip GET /api/v1/trips/{tripId}")
    class QueryTripTests {

        @Test
        @DisplayName("Customer owner can fetch trip details - returns 200 OK")
        void getTrip_Success() throws Exception {
            when(bookingService.getTripById(eq(tripId), eq(customerId)))
                    .thenReturn(createMockResponse());

            mockMvc.perform(get(BASE_URL + "/" + tripId)
                            .with(asCustomer(customerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tripId").value(tripId.toString()));
        }

        @Test
        @DisplayName("Non-owner receives 403 Forbidden")
        void getTrip_NonOwner_Returns403() throws Exception {
            when(bookingService.getTripById(eq(tripId), eq(customerId)))
                    .thenThrow(new TripAccessDeniedException(tripId, customerId));

            mockMvc.perform(get(BASE_URL + "/" + tripId)
                            .with(asCustomer(customerId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TRIP_ACCESS_DENIED"));
        }
    }
}
