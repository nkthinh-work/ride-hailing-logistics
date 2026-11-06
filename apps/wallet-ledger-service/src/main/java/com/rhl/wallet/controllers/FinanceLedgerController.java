package com.rhl.wallet.controllers;

import com.rhl.wallet.configs.WalletProperties;
import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.domain.LedgerAccount;
import com.rhl.wallet.domain.LedgerTransaction;
import com.rhl.wallet.dtos.DriverWalletResponse;
import com.rhl.wallet.dtos.LedgerAccountResponse;
import com.rhl.wallet.dtos.LedgerTransactionResponse;
import com.rhl.wallet.dtos.WalletReconciliationResponse;
import com.rhl.wallet.repositories.LedgerAccountRepository;
import com.rhl.wallet.repositories.LedgerTransactionRepository;
import com.rhl.wallet.services.DriverWalletService;
import com.rhl.wallet.services.WalletBalanceReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Finance Staff and Administrator REST API for inspecting wallets, ledger accounts, and reconciliation.
 * Strictly read-only to protect ledger immutability and accounting invariants.
 */
@RestController
@RequestMapping("/api/v1/finance")
public class FinanceLedgerController {

    private final DriverWalletService walletService;
    private final WalletBalanceReconciliationService reconciliationService;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerAccountRepository accountRepository;
    private final WalletProperties walletProperties;

    public FinanceLedgerController(
            DriverWalletService walletService,
            WalletBalanceReconciliationService reconciliationService,
            LedgerTransactionRepository transactionRepository,
            LedgerAccountRepository accountRepository,
            WalletProperties walletProperties
    ) {
        this.walletService = walletService;
        this.reconciliationService = reconciliationService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.walletProperties = walletProperties;
    }

    @GetMapping("/wallets/{walletId}")
    @PreAuthorize("hasRole('FINANCE_STAFF') or hasRole('ADMINISTRATOR')")
    public ResponseEntity<DriverWalletResponse> getWalletById(@PathVariable UUID walletId) {
        DriverWallet wallet = walletService.getWalletById(walletId);
        String policyStatus = walletProperties.getCommission().getPolicyStatus();
        return ResponseEntity.ok(DriverWalletResponse.from(wallet, policyStatus));
    }

    @GetMapping("/wallets/{walletId}/reconcile")
    @PreAuthorize("hasRole('FINANCE_STAFF') or hasRole('ADMINISTRATOR')")
    public ResponseEntity<WalletReconciliationResponse> reconcileWallet(@PathVariable UUID walletId) {
        WalletReconciliationResponse response = reconciliationService.reconcileWalletBalance(walletId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/ledger/transactions/{transactionId}")
    @PreAuthorize("hasRole('FINANCE_STAFF') or hasRole('ADMINISTRATOR')")
    public ResponseEntity<LedgerTransactionResponse> getTransactionById(@PathVariable UUID transactionId) {
        LedgerTransaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger transaction " + transactionId + " not found"));
        return ResponseEntity.ok(LedgerTransactionResponse.from(tx));
    }

    @GetMapping("/ledger/accounts/{accountId}")
    @PreAuthorize("hasRole('FINANCE_STAFF') or hasRole('ADMINISTRATOR')")
    public ResponseEntity<LedgerAccountResponse> getAccountById(@PathVariable UUID accountId) {
        LedgerAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger account " + accountId + " not found"));
        return ResponseEntity.ok(LedgerAccountResponse.from(account));
    }
}
