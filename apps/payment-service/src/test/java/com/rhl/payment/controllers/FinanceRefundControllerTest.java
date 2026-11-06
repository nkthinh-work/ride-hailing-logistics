package com.rhl.payment.controllers;

import com.rhl.payment.configs.SecurityConfig;
import com.rhl.payment.dtos.RefundResponse;
import com.rhl.payment.enums.RefundStatus;
import com.rhl.payment.exceptions.GlobalExceptionHandler;
import com.rhl.payment.services.PaymentRefundService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FinanceRefundController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("FinanceRefundController WebMvc Security & RBAC Tests")
class FinanceRefundControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentRefundService refundService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID staffId = UUID.randomUUID();
    private final UUID refundId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    private RequestPostProcessor financeStaffJwt() {
        return jwt().jwt(builder -> builder
                .subject(staffId.toString())
                .claim("userId", staffId.toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_STAFF"));
    }

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(builder -> builder
                .subject(staffId.toString())
                .claim("userId", staffId.toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"));
    }

    private RequestPostProcessor customerJwt() {
        return jwt().jwt(builder -> builder
                .subject(UUID.randomUUID().toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private RefundResponse createRefundResponse(RefundStatus status) {
        Instant now = Instant.now();
        com.rhl.payment.entities.PaymentRefund refund = com.rhl.payment.entities.PaymentRefund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .customerId(customerId)
                .tripId(tripId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .reason("Customer requested refund")
                .idempotencyKey("idemp-fin-1")
                .requestedBy(customerId.toString())
                .provider("MOMO")
                .initialStatus(status)
                .now(now)
                .build();
        return RefundResponse.from(refund);
    }

    @Test
    @DisplayName("POST /api/v1/finance/refunds/{refundId}/approve: Finance staff can approve refund")
    void approveRefund_financeStaff_ok() throws Exception {
        when(refundService.approveAndExecuteRefund(eq(staffId), eq(refundId), any()))
                .thenReturn(createRefundResponse(RefundStatus.SUCCEEDED));

        mockMvc.perform(post("/api/v1/finance/refunds/{refundId}/approve", refundId)
                        .with(financeStaffJwt())
                        .param("note", "Approved after customer review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId").value(refundId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("POST /api/v1/finance/refunds/{refundId}/approve: Administrator can approve refund")
    void approveRefund_admin_ok() throws Exception {
        when(refundService.approveAndExecuteRefund(eq(staffId), eq(refundId), any()))
                .thenReturn(createRefundResponse(RefundStatus.SUCCEEDED));

        mockMvc.perform(post("/api/v1/finance/refunds/{refundId}/approve", refundId)
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("POST /api/v1/finance/refunds/{refundId}/approve: Customer role is forbidden (403)")
    void approveRefund_customer_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/finance/refunds/{refundId}/approve", refundId)
                        .with(customerJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/finance/refunds/{refundId}/reconcile: Finance staff can reconcile refund")
    void reconcileRefund_financeStaff_ok() throws Exception {
        when(refundService.reconcileRefund(refundId))
                .thenReturn(createRefundResponse(RefundStatus.SUCCEEDED));

        mockMvc.perform(post("/api/v1/finance/refunds/{refundId}/reconcile", refundId)
                        .with(financeStaffJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("GET /api/v1/finance/refunds/{refundId}: Finance staff can view refund audit details")
    void getRefund_financeStaff_ok() throws Exception {
        when(refundService.getRefundById(staffId, refundId, true))
                .thenReturn(createRefundResponse(RefundStatus.PROCESSING));

        mockMvc.perform(get("/api/v1/finance/refunds/{refundId}", refundId)
                        .with(financeStaffJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId").value(refundId.toString()));
    }
}
