package com.rhl.wallet.controllers;

import com.rhl.wallet.configs.SecurityConfig;
import com.rhl.wallet.dtos.FinancialEventIngestRequest;
import com.rhl.wallet.dtos.FinancialEventIngestResponse;
import com.rhl.wallet.exceptions.GlobalExceptionHandler;
import com.rhl.wallet.services.FinancialEventIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalFinancialEventController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "security.internal.secret=rhl-test-secret-2026"
})
@DisplayName("InternalFinancialEventController M2M Security Tests")
class InternalFinancialEventControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FinancialEventIngestionService ingestionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("POST /internal/v1/financial-events with valid secret returns 200 OK")
    void ingestEvent_validSecret_returns200() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(ingestionService.ingestEvent(any(FinancialEventIngestRequest.class)))
                .thenReturn(new FinancialEventIngestResponse(eventId, "PROCESSED", "Success", Instant.now()));

        mockMvc.perform(post("/internal/v1/financial-events")
                        .header("X-Internal-Secret", "rhl-test-secret-2026")
                        .header("X-Caller-Service", "payment-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "eventId": "%s",
                                    "eventType": "PAYMENT_SUCCEEDED",
                                    "sourceService": "payment-service",
                                    "entityId": "%s",
                                    "payload": "{}"
                                }
                                """.formatted(eventId, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
    }

    @Test
    @DisplayName("POST /internal/v1/financial-events with missing secret returns 401 Unauthorized")
    void ingestEvent_missingSecret_returns401() throws Exception {
        mockMvc.perform(post("/internal/v1/financial-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("POST /internal/v1/financial-events with invalid secret returns 401 Unauthorized")
    void ingestEvent_invalidSecret_returns401() throws Exception {
        mockMvc.perform(post("/internal/v1/financial-events")
                        .header("X-Internal-Secret", "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }
}
