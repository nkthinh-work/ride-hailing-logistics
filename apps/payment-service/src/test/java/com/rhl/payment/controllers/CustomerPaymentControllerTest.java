package com.rhl.payment.controllers;

import com.rhl.payment.configs.SecurityConfig;
import com.rhl.payment.dtos.InitiatePaymentRequest;
import com.rhl.payment.dtos.PaymentResponse;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;
import com.rhl.payment.exceptions.GlobalExceptionHandler;
import com.rhl.payment.exceptions.IdempotencyConflictException;
import com.rhl.payment.exceptions.PaymentAccessDeniedException;
import com.rhl.payment.exceptions.TripNotPayableException;
import com.rhl.payment.services.PaymentService;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CustomerPaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("CustomerPaymentController Security & REST WebMvc Tests")
class CustomerPaymentControllerTest {

    private static final String BASE_URL = "/api/v1/payments";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID customerId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final String idempotencyKey = "key-cust-123";

    private final String validRequestJson = """
            {
                "tripId": "%s",
                "paymentMethod": "DIGITAL_WALLET"
            }
            """.formatted(tripId);

    private PaymentResponse createMockPaymentResponse() {
        return new PaymentResponse(
                paymentId,
                tripId,
                customerId,
                new BigDecimal("50000.00"),
                "VND",
                PaymentMethod.DIGITAL_WALLET,
                PaymentStatus.PROCESSING,
                idempotencyKey,
                "sandbox-ref-123",
                null,
                Instant.now(),
                Instant.now(),
                null,
                List.of()
        );
    }

    private RequestPostProcessor asCustomer(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private RequestPostProcessor asDriver(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    @Nested
    @DisplayName("Authentication & Role Authorization")
    class AuthorizationTests {

        @Test
        @DisplayName("Unauthenticated request returns 401 Unauthorized")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("User with ROLE_DRIVER cannot initiate customer payment - returns 403 Forbidden")
        void driverRoleReturns403() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .with(asDriver(customerId))
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("User with ROLE_CUSTOMER successfully initiates payment - returns 201 Created")
        void customerRoleReturns201() throws Exception {
            when(paymentService.initiatePayment(eq(customerId), eq(idempotencyKey), any(InitiatePaymentRequest.class)))
                    .thenReturn(createMockPaymentResponse());

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                    .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                    .andExpect(jsonPath("$.amount").value(50000.00))
                    .andExpect(jsonPath("$.currency").value("VND"))
                    .andExpect(jsonPath("$.status").value("PROCESSING"));
        }
    }

    @Nested
    @DisplayName("Validation & Conflict Contracts")
    class ValidationTests {

        @Test
        @DisplayName("Missing Idempotency-Key header returns 400 Bad Request")
        void missingIdempotencyKeyReturns400() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Missing tripId in request body returns 400 Bad Request")
        void missingTripIdReturns400() throws Exception {
            String invalidJson = """
                    {
                        "paymentMethod": "CASH"
                    }
                    """;

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("Idempotency conflict returns 409 Conflict")
        void idempotencyConflictReturns409() throws Exception {
            when(paymentService.initiatePayment(eq(customerId), eq(idempotencyKey), any()))
                    .thenThrow(new IdempotencyConflictException("Differing parameters"));

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
        }

        @Test
        @DisplayName("Non-payable trip returns 400 Bad Request")
        void tripNotPayableReturns400() throws Exception {
            when(paymentService.initiatePayment(eq(customerId), eq(idempotencyKey), any()))
                    .thenThrow(new TripNotPayableException("Trip not completed"));

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("TRIP_NOT_PAYABLE"));
        }

        @Test
        @DisplayName("Access denied (non-owner) returns 403 Forbidden")
        void accessDeniedReturns403() throws Exception {
            when(paymentService.initiatePayment(eq(customerId), eq(idempotencyKey), any()))
                    .thenThrow(new PaymentAccessDeniedException("Customer does not own trip"));

            mockMvc.perform(post(BASE_URL)
                            .with(asCustomer(customerId))
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validRequestJson))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("PAYMENT_ACCESS_DENIED"));
        }
    }

    @Nested
    @DisplayName("Lookup Queries")
    class QueryTests {

        @Test
        @DisplayName("GET /api/v1/payments/{paymentId} returns own payment details")
        void getPaymentByIdSuccess() throws Exception {
            when(paymentService.getPaymentById(eq(customerId), eq(paymentId)))
                    .thenReturn(createMockPaymentResponse());

            mockMvc.perform(get(BASE_URL + "/" + paymentId)
                            .with(asCustomer(customerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                    .andExpect(jsonPath("$.amount").value(50000.00));
        }

        @Test
        @DisplayName("GET /api/v1/payments/trips/{tripId} returns list of trip payments")
        void getTripPaymentsSuccess() throws Exception {
            when(paymentService.getPaymentsByTripId(eq(customerId), eq(tripId)))
                    .thenReturn(List.of(createMockPaymentResponse()));

            mockMvc.perform(get(BASE_URL + "/trips/" + tripId)
                            .with(asCustomer(customerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].paymentId").value(paymentId.toString()));
        }

        @Test
        @DisplayName("GET /api/v1/payments returns customer payment history")
        void getMyPaymentsSuccess() throws Exception {
            when(paymentService.getMyPayments(eq(customerId)))
                    .thenReturn(List.of(createMockPaymentResponse()));

            mockMvc.perform(get(BASE_URL)
                            .with(asCustomer(customerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].paymentId").value(paymentId.toString()));
        }
    }
}
