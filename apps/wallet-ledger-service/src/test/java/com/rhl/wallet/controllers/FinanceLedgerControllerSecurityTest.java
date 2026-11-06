package com.rhl.wallet.controllers;

import com.rhl.wallet.configs.SecurityConfig;
import com.rhl.wallet.configs.WalletProperties;
import com.rhl.wallet.domain.AccountOwnerType;
import com.rhl.wallet.domain.AccountType;
import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.domain.LedgerAccount;
import com.rhl.wallet.domain.LedgerTransaction;
import com.rhl.wallet.domain.LedgerTransactionType;
import com.rhl.wallet.domain.NormalBalance;
import com.rhl.wallet.dtos.WalletReconciliationResponse;
import com.rhl.wallet.exceptions.GlobalExceptionHandler;
import com.rhl.wallet.repositories.LedgerAccountRepository;
import com.rhl.wallet.repositories.LedgerTransactionRepository;
import com.rhl.wallet.services.DriverWalletService;
import com.rhl.wallet.services.WalletBalanceReconciliationService;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FinanceLedgerController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WalletProperties.class})
@DisplayName("FinanceLedgerController WebMvc Security & RBAC Tests")
class FinanceLedgerControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriverWalletService walletService;

    @MockitoBean
    private WalletBalanceReconciliationService reconciliationService;

    @MockitoBean
    private LedgerTransactionRepository transactionRepository;

    @MockitoBean
    private LedgerAccountRepository accountRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID staffId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private RequestPostProcessor financeStaffJwt() {
        return jwt().jwt(builder -> builder
                .subject(staffId.toString())
                .claim("userId", staffId.toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_STAFF"));
    }

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(builder -> builder
                .subject(UUID.randomUUID().toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"));
    }

    private RequestPostProcessor driverJwt() {
        return jwt().jwt(builder -> builder
                .subject(driverId.toString())
        ).authorities(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    @Test
    @DisplayName("GET /api/v1/finance/wallets/{walletId}: Finance staff role returns 200 OK")
    void getWalletById_financeStaff_returns200() throws Exception {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", Instant.now());
        when(walletService.getWalletById(walletId)).thenReturn(wallet);

        mockMvc.perform(get("/api/v1/finance/wallets/{walletId}", walletId).with(financeStaffJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(driverId.toString()));
    }

    @Test
    @DisplayName("GET /api/v1/finance/wallets/{walletId}: Administrator role returns 200 OK")
    void getWalletById_admin_returns200() throws Exception {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", Instant.now());
        when(walletService.getWalletById(walletId)).thenReturn(wallet);

        mockMvc.perform(get("/api/v1/finance/wallets/{walletId}", walletId).with(adminJwt()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/finance/wallets/{walletId}: Driver role is forbidden (403)")
    void getWalletById_driverRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/finance/wallets/{walletId}", walletId).with(driverJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/finance/wallets/{walletId}/reconcile: Finance staff returns 200 OK")
    void reconcileWallet_financeStaff_returns200() throws Exception {
        WalletReconciliationResponse recon = new WalletReconciliationResponse(
                walletId,
                driverId,
                "VND",
                new BigDecimal("50000.00"),
                new BigDecimal("50000.00"),
                BigDecimal.ZERO,
                true,
                Instant.now()
        );
        when(reconciliationService.reconcileWalletBalance(walletId)).thenReturn(recon);

        mockMvc.perform(get("/api/v1/finance/wallets/{walletId}/reconcile", walletId).with(financeStaffJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inBalance").value(true))
                .andExpect(jsonPath("$.projectedAvailableBalance").value(50000.00));
    }

    @Test
    @DisplayName("GET /api/v1/finance/ledger/transactions/{transactionId}: Finance staff returns 200 OK")
    void getTransactionById_financeStaff_returns200() throws Exception {
        Instant now = Instant.now();
        LedgerTransaction tx = new LedgerTransaction(
                transactionId,
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-REF-10",
                UUID.randomUUID(),
                "payment-service",
                "Description",
                "VND",
                "corr-1",
                now,
                now,
                "SYSTEM",
                null
        );
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(tx));

        mockMvc.perform(get("/api/v1/finance/ledger/transactions/{transactionId}", transactionId).with(financeStaffJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessReference").value("TRIP-REF-10"));
    }

    @Test
    @DisplayName("GET /api/v1/finance/ledger/accounts/{accountId}: Finance staff returns 200 OK")
    void getAccountById_financeStaff_returns200() throws Exception {
        LedgerAccount account = LedgerAccount.create(
                "DRIVER_PAYABLE:" + driverId,
                AccountType.LIABILITY,
                NormalBalance.CREDIT,
                AccountOwnerType.DRIVER,
                driverId,
                "VND",
                Instant.now()
        );
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        mockMvc.perform(get("/api/v1/finance/ledger/accounts/{accountId}", accountId).with(financeStaffJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCode").value("DRIVER_PAYABLE:" + driverId));
    }
}
