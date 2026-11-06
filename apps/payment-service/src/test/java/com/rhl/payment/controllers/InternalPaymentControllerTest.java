package com.rhl.payment.controllers;

import com.rhl.payment.configs.SecurityConfig;
import com.rhl.payment.dtos.PaymentResponse;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;
import com.rhl.payment.exceptions.GlobalExceptionHandler;
import com.rhl.payment.exceptions.PaymentNotFoundException;
import com.rhl.payment.services.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalPaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("InternalPaymentController Security & M2M Contract Tests")
class InternalPaymentControllerTest {

    private static final String BASE_URL = "/internal/v1/payments";
    private static final String VALID_SECRET = "test-internal-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    private PaymentResponse createMockPaymentResponse() {
        return new PaymentResponse(
                paymentId,
                tripId,
                customerId,
                new BigDecimal("60000.00"),
                "VND",
                PaymentMethod.CASH,
                PaymentStatus.PROCESSING,
                "key-m2m",
                null,
                null,
                Instant.now(),
                Instant.now(),
                null,
                List.of()
        );
    }

    @Test
    @DisplayName("Internal endpoint without secret returns 401 UNAUTHORIZED_INTERNAL_SERVICE")
    void missingSecretReturns401() throws Exception {
        mockMvc.perform(get(BASE_URL + "/" + paymentId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("Internal endpoint with invalid secret returns 401 UNAUTHORIZED_INTERNAL_SERVICE")
    void wrongSecretReturns401() throws Exception {
        mockMvc.perform(get(BASE_URL + "/" + paymentId)
                        .header("X-Internal-Secret", "forged-secret-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("GET /internal/v1/payments/{paymentId} with valid secret returns 200 OK")
    void getPaymentInternalSuccess() throws Exception {
        when(paymentService.getPaymentByIdInternal(eq(paymentId)))
                .thenReturn(createMockPaymentResponse());

        mockMvc.perform(get(BASE_URL + "/" + paymentId)
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.amount").value(60000.00));
    }

    @Test
    @DisplayName("GET /internal/v1/payments/trips/{tripId} with valid secret returns 200 OK")
    void getTripPaymentsInternalSuccess() throws Exception {
        when(paymentService.getPaymentsByTripIdInternal(eq(tripId)))
                .thenReturn(List.of(createMockPaymentResponse()));

        mockMvc.perform(get(BASE_URL + "/trips/" + tripId)
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(paymentId.toString()));
    }

    @Test
    @DisplayName("GET /internal/v1/payments/{paymentId} when not found returns 404 NOT_FOUND")
    void paymentNotFoundReturns404() throws Exception {
        when(paymentService.getPaymentByIdInternal(eq(paymentId)))
                .thenThrow(new PaymentNotFoundException("Payment " + paymentId + " not found"));

        mockMvc.perform(get(BASE_URL + "/" + paymentId)
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }
}
