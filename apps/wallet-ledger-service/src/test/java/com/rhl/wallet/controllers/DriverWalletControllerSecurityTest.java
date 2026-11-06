package com.rhl.wallet.controllers;

import com.rhl.wallet.configs.SecurityConfig;
import com.rhl.wallet.configs.WalletProperties;
import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.exceptions.GlobalExceptionHandler;
import com.rhl.wallet.repositories.LedgerEntryRepository;
import com.rhl.wallet.services.DriverWalletService;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DriverWalletController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WalletProperties.class})
@DisplayName("DriverWalletController WebMvc Security & RBAC Tests")
class DriverWalletControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriverWalletService walletService;

    @MockitoBean
    private LedgerEntryRepository entryRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID driverId = UUID.randomUUID();

    private RequestPostProcessor driverJwt() {
        return jwt().jwt(builder -> builder
                .subject(driverId.toString())
                .claim("userId", driverId.toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    private RequestPostProcessor customerJwt() {
        return jwt().jwt(builder -> builder
                .subject(UUID.randomUUID().toString())
                .claim("userId", UUID.randomUUID().toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    @DisplayName("GET /api/v1/wallets/me: Driver role returns 200 OK and own wallet")
    void getMyWallet_driverRole_returns200() throws Exception {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", Instant.now());
        when(walletService.getOrCreateWallet(eq(driverId), eq("VND"))).thenReturn(wallet);

        mockMvc.perform(get("/api/v1/wallets/me").with(driverJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(driverId.toString()))
                .andExpect(jsonPath("$.currency").value("VND"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.policyStatus").value("COMMISSION_POLICY_PENDING"));
    }

    @Test
    @DisplayName("GET /api/v1/wallets/me: Customer role is forbidden (403)")
    void getMyWallet_customerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me").with(customerJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/wallets/me: Unauthenticated request returns 401 Unauthorized")
    void getMyWallet_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/wallets/me/transactions: Driver role returns 200 OK")
    void getMyTransactions_driverRole_returns200() throws Exception {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", Instant.now());
        when(walletService.getOrCreateWallet(eq(driverId), eq("VND"))).thenReturn(wallet);
        when(entryRepository.findByWalletIdOrderByCreatedAtDesc(eq(wallet.getId()), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/wallets/me/transactions").with(driverJwt()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/wallets/me/transactions: Customer role is forbidden (403)")
    void getMyTransactions_customerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me/transactions").with(customerJwt()))
                .andExpect(status().isForbidden());
    }
}
