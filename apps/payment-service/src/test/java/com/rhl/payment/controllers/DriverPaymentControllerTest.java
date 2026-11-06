package com.rhl.payment.controllers;

import com.rhl.payment.configs.SecurityConfig;
import com.rhl.payment.dtos.PaymentResponse;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;
import com.rhl.payment.exceptions.GlobalExceptionHandler;
import com.rhl.payment.exceptions.InvalidPaymentStateTransitionException;
import com.rhl.payment.exceptions.PaymentAccessDeniedException;
import com.rhl.payment.exceptions.TripNotPayableException;
import com.rhl.payment.services.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DriverPaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("DriverPaymentController Security & Cash Confirmation Tests")
class DriverPaymentControllerTest {

    private static final String BASE_URL = "/api/v1/payments";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID driverId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    private RequestPostProcessor driverJwt(UUID id) {
        return jwt().jwt(builder -> builder
                .subject(id.toString())
                .claim("userId", id.toString())
                .claim("roles", List.of("DRIVER"))
        ).authorities(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    private RequestPostProcessor customerJwt(UUID id) {
        return jwt().jwt(builder -> builder
                .subject(id.toString())
                .claim("userId", id.toString())
                .claim("roles", List.of("CUSTOMER"))
        ).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private PaymentResponse createPaymentResponse(PaymentStatus status) {
        return new PaymentResponse(
                paymentId,
                tripId,
                customerId,
                new BigDecimal("50000.00"),
                "VND",
                PaymentMethod.CASH,
                status,
                "key-cash-123",
                status == PaymentStatus.SUCCEEDED ? "CASH_SETTLED" : null,
                null,
                Instant.now(),
                Instant.now(),
                status == PaymentStatus.SUCCEEDED ? Instant.now() : null,
                List.of()
        );
    }

    @Test
    @DisplayName("Assigned driver successfully confirms cash collection for completed trip")
    void assignedDriverConfirmsCashSuccessfully() throws Exception {
        when(paymentService.confirmCashPayment(eq(driverId), eq(paymentId)))
                .thenReturn(createPaymentResponse(PaymentStatus.SUCCEEDED));

        mockMvc.perform(post(BASE_URL + "/{paymentId}/cash/confirm", paymentId)
                        .with(driverJwt(driverId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.providerReference").value("CASH_SETTLED"));
    }

    @Test
    @DisplayName("Security: Customer is forbidden from self-confirming cash collection (HTTP 403)")
    void customerCannotConfirmCash() throws Exception {
        mockMvc.perform(post(BASE_URL + "/{paymentId}/cash/confirm", paymentId)
                        .with(customerJwt(customerId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Unauthenticated caller cannot confirm cash collection (HTTP 401)")
    void unauthenticatedCannotConfirmCash() throws Exception {
        mockMvc.perform(post(BASE_URL + "/{paymentId}/cash/confirm", paymentId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Security: Wrong driver (not assigned to trip) is forbidden (HTTP 403)")
    void wrongDriverCannotConfirmCash() throws Exception {
        UUID wrongDriverId = UUID.randomUUID();
        when(paymentService.confirmCashPayment(eq(wrongDriverId), eq(paymentId)))
                .thenThrow(new PaymentAccessDeniedException("Driver is not assigned to trip"));

        mockMvc.perform(post(BASE_URL + "/{paymentId}/cash/confirm", paymentId)
                        .with(driverJwt(wrongDriverId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Rejects cash confirmation when payment is not CASH method (HTTP 400)")
    void nonCashPaymentCannotBeConfirmedByDriver() throws Exception {
        when(paymentService.confirmCashPayment(eq(driverId), eq(paymentId)))
                .thenThrow(new InvalidPaymentStateTransitionException(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED));

        mockMvc.perform(post(BASE_URL + "/{paymentId}/cash/confirm", paymentId)
                        .with(driverJwt(driverId)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Rejects cash confirmation when trip is not COMPLETED (HTTP 400)")
    void tripNotCompletedCannotBeConfirmed() throws Exception {
        when(paymentService.confirmCashPayment(eq(driverId), eq(paymentId)))
                .thenThrow(new TripNotPayableException("Trip is not in COMPLETED status"));

        mockMvc.perform(post(BASE_URL + "/{paymentId}/cash/confirm", paymentId)
                        .with(driverJwt(driverId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Idempotent replay: confirming already SUCCEEDED cash payment returns 200 OK")
    void duplicateConfirmationReturnsIdempotentSuccess() throws Exception {
        when(paymentService.confirmCashPayment(eq(driverId), eq(paymentId)))
                .thenReturn(createPaymentResponse(PaymentStatus.SUCCEEDED));

        mockMvc.perform(post(BASE_URL + "/{paymentId}/cash/confirm", paymentId)
                        .with(driverJwt(driverId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("Assigned driver successfully retrieves payments for completed trip")
    void assignedDriverGetsTripPaymentsSuccessfully() throws Exception {
        when(paymentService.getPaymentsForDriverTrip(eq(driverId), eq(tripId)))
                .thenReturn(List.of(createPaymentResponse(PaymentStatus.PROCESSING)));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE_URL + "/driver/trips/{tripId}", tripId)
                        .with(driverJwt(driverId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$[0].status").value("PROCESSING"));
    }

    @Test
    @DisplayName("Security: Unassigned driver cannot view trip payments (HTTP 403)")
    void unassignedDriverCannotGetTripPayments() throws Exception {
        UUID wrongDriverId = UUID.randomUUID();
        when(paymentService.getPaymentsForDriverTrip(eq(wrongDriverId), eq(tripId)))
                .thenThrow(new PaymentAccessDeniedException("Driver is not assigned to trip " + tripId));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE_URL + "/driver/trips/{tripId}", tripId)
                        .with(driverJwt(wrongDriverId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Customer cannot call driver trip payments endpoint (HTTP 403)")
    void customerCannotCallDriverTripPayments() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE_URL + "/driver/trips/{tripId}", tripId)
                        .with(customerJwt(customerId)))
                .andExpect(status().isForbidden());
    }
}
