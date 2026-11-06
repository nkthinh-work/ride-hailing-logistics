package com.rhl.payment.controllers;

import com.rhl.payment.configs.SecurityConfig;
import com.rhl.payment.exceptions.GlobalExceptionHandler;
import com.rhl.payment.exceptions.PaymentNotFoundException;
import com.rhl.payment.exceptions.PaymentSecurityException;
import com.rhl.payment.services.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PaymentWebhookController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("PaymentWebhookController Security & Webhook Tests")
class PaymentWebhookControllerTest {

    private static final String BASE_URL = "/api/v1/payments/webhook";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Public narrow endpoint: Provider callback does not require JWT Bearer token")
    void providerCallbackDoesNotRequireJwt() throws Exception {
        String payload = """
                {"orderId": "ORD-1", "amount": 50000, "resultCode": 0}
                """;

        when(paymentService.processProviderWebhook(eq("momo"), any(), any()))
                .thenReturn(Map.of("message", "Callback processed successfully", "resultCode", 0));

        mockMvc.perform(post(BASE_URL + "/momo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(0))
                .andExpect(jsonPath("$.message").value("Callback processed successfully"));
    }

    @Test
    @DisplayName("Webhook Security: Rejects callback when signature verification fails (HTTP 400)")
    void rejectsInvalidSignatureWithBadRequest() throws Exception {
        String payload = """
                {"orderId": "ORD-2", "amount": 50000, "signature": "bad-signature"}
                """;

        when(paymentService.processProviderWebhook(eq("momo"), any(), any()))
                .thenThrow(new PaymentSecurityException("Invalid HMAC signature in MoMo callback"));

        mockMvc.perform(post(BASE_URL + "/momo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PAYMENT_SECURITY_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid HMAC signature in MoMo callback"));
    }

    @Test
    @DisplayName("Webhook Security: Rejects callback when amount has been tampered (HTTP 400)")
    void rejectsTamperedAmountWithBadRequest() throws Exception {
        String payload = """
                {"orderId": "ORD-3", "amount": 1000}
                """;

        when(paymentService.processProviderWebhook(eq("momo"), any(), any()))
                .thenThrow(new PaymentSecurityException("Amount mismatch in provider callback"));

        mockMvc.perform(post(BASE_URL + "/momo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PAYMENT_SECURITY_ERROR"))
                .andExpect(jsonPath("$.message").value("Amount mismatch in provider callback"));
    }

    @Test
    @DisplayName("Rejects callback when provider order ID is not found (HTTP 404)")
    void rejectsUnknownOrderIdWithNotFound() throws Exception {
        String payload = """
                {"orderId": "ORD-UNKNOWN", "amount": 50000}
                """;

        when(paymentService.processProviderWebhook(eq("momo"), any(), any()))
                .thenThrow(new PaymentNotFoundException("Payment attempt not found for orderId: ORD-UNKNOWN"));

        mockMvc.perform(post(BASE_URL + "/momo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Payment attempt not found for orderId: ORD-UNKNOWN"));
    }

    @Test
    @DisplayName("Duplicate callback returns idempotent acknowledgment 200 OK")
    void duplicateCallbackReturnsIdempotentOk() throws Exception {
        String payload = """
                {"orderId": "ORD-DUP", "amount": 50000, "resultCode": 0}
                """;

        when(paymentService.processProviderWebhook(eq("momo"), any(), any()))
                .thenReturn(Map.of("message", "Already acknowledged", "resultCode", 0));

        mockMvc.perform(post(BASE_URL + "/momo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(0))
                .andExpect(jsonPath("$.message").value("Already acknowledged"));
    }
}
