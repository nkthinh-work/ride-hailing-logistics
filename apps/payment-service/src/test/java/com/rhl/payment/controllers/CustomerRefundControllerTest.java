package com.rhl.payment.controllers;

import com.rhl.payment.configs.SecurityConfig;
import com.rhl.payment.dtos.CreateRefundRequest;
import com.rhl.payment.dtos.RefundEligibilityResponse;
import com.rhl.payment.dtos.RefundResponse;
import com.rhl.payment.enums.RefundStatus;
import com.rhl.payment.exceptions.GlobalExceptionHandler;
import com.rhl.payment.exceptions.OverRefundException;
import com.rhl.payment.exceptions.RefundNotEligibleException;
import com.rhl.payment.services.PaymentRefundService;
import org.junit.jupiter.api.DisplayName;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CustomerRefundController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("CustomerRefundController WebMvc Security & API Tests")
class CustomerRefundControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentRefundService refundService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID customerId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID refundId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    private RequestPostProcessor customerJwt() {
        return jwt().jwt(builder -> builder
                .subject(customerId.toString())
                .claim("userId", customerId.toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private RequestPostProcessor driverJwt() {
        return jwt().jwt(builder -> builder
                .subject(UUID.randomUUID().toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    @Test
    @DisplayName("POST /api/v1/payments/{paymentId}/refunds: Customer can submit refund request")
    void requestRefund_customer_created() throws Exception {
        com.rhl.payment.entities.PaymentRefund refund = com.rhl.payment.entities.PaymentRefund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .reason("Driver cancelled")
                .idempotencyKey("idemp-1")
                .requestedBy(customerId.toString())
                .provider("MOMO")
                .policyNote("REFUND_POLICY_PENDING")
                .initialStatus(RefundStatus.REQUESTED)
                .now(Instant.now())
                .build();
        RefundResponse response = RefundResponse.from(refund);

        when(refundService.requestRefund(eq(customerId), eq(paymentId), eq("idemp-1"), any(CreateRefundRequest.class), eq(false)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/payments/{paymentId}/refunds", paymentId)
                        .with(customerJwt())
                        .header("Idempotency-Key", "idemp-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "amount": 50000.00,
                                    "reason": "Driver cancelled"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId").value(refundId.toString()))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.amount").value(50000.00));
    }

    @Test
    @DisplayName("POST /api/v1/payments/{paymentId}/refunds: OverRefundException returns 409 Conflict")
    void requestRefund_overRefund_returnsConflict() throws Exception {
        when(refundService.requestRefund(eq(customerId), eq(paymentId), eq("idemp-over"), any(CreateRefundRequest.class), eq(false)))
                .thenThrow(new OverRefundException(new BigDecimal("120000.00"), new BigDecimal("50000.00")));

        mockMvc.perform(post("/api/v1/payments/{paymentId}/refunds", paymentId)
                        .with(customerJwt())
                        .header("Idempotency-Key", "idemp-over")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "amount": 120000.00,
                                    "reason": "Excessive amount"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OVER_REFUND_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("exceeds remaining refundable amount")));
    }

    @Test
    @DisplayName("POST /api/v1/payments/{paymentId}/refunds: Ineligible payment returns 400 Bad Request")
    void requestRefund_notEligible_returnsBadRequest() throws Exception {
        when(refundService.requestRefund(eq(customerId), eq(paymentId), eq("idemp-ineligible"), any(CreateRefundRequest.class), eq(false)))
                .thenThrow(new RefundNotEligibleException("Payment is in state FAILED"));

        mockMvc.perform(post("/api/v1/payments/{paymentId}/refunds", paymentId)
                        .with(customerJwt())
                        .header("Idempotency-Key", "idemp-ineligible")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "amount": 50000.00,
                                    "reason": "Test"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("REFUND_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("POST /api/v1/payments/{paymentId}/refunds: Driver role is forbidden (403)")
    void requestRefund_driver_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/payments/{paymentId}/refunds", paymentId)
                        .with(driverJwt())
                        .header("Idempotency-Key", "idemp-driver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "amount": 50000.00,
                                    "reason": "Driver cannot refund"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/payments/{paymentId}/refunds/eligibility: Customer checks eligibility")
    void checkEligibility_customer_ok() throws Exception {
        RefundEligibilityResponse response = new RefundEligibilityResponse(
                paymentId,
                true,
                new BigDecimal("100000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                "VND",
                "Eligible for refund",
                "REFUND_POLICY_PENDING"
        );

        when(refundService.checkRefundEligibility(customerId, paymentId, false))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/payments/{paymentId}/refunds/eligibility", paymentId)
                        .with(customerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.remainingRefundableAmount").value(100000.00));
    }
}
