package com.rhl.wallet.controllers;

import com.rhl.wallet.configs.WalletProperties;
import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.domain.LedgerEntry;
import com.rhl.wallet.dtos.DriverWalletResponse;
import com.rhl.wallet.dtos.LedgerEntryResponse;
import com.rhl.wallet.repositories.LedgerEntryRepository;
import com.rhl.wallet.services.DriverWalletService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Driver self-service wallet API (FR-WAL-001, FR-WAL-007).
 * Strictly restricts drivers to accessing only their own wallet and ledger transactions.
 */
@RestController
@RequestMapping("/api/v1/wallets/me")
public class DriverWalletController {

    private final DriverWalletService walletService;
    private final LedgerEntryRepository entryRepository;
    private final WalletProperties walletProperties;

    public DriverWalletController(
            DriverWalletService walletService,
            LedgerEntryRepository entryRepository,
            WalletProperties walletProperties
    ) {
        this.walletService = walletService;
        this.entryRepository = entryRepository;
        this.walletProperties = walletProperties;
    }

    /**
     * Retrieves the authenticated driver's wallet (lazy initialized on first access).
     */
    @GetMapping
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<DriverWalletResponse> getMyWallet(@AuthenticationPrincipal Jwt jwt) {
        UUID driverId = extractDriverId(jwt);
        DriverWallet wallet = walletService.getOrCreateWallet(driverId, walletProperties.getDefaultCurrency());
        String policyStatus = walletProperties.getCommission().getPolicyStatus();
        return ResponseEntity.ok(DriverWalletResponse.from(wallet, policyStatus));
    }

    /**
     * Retrieves recent ledger transactions and movements affecting the authenticated driver's wallet.
     */
    @GetMapping("/transactions")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<List<LedgerEntryResponse>> getMyTransactions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID driverId = extractDriverId(jwt);
        DriverWallet wallet = walletService.getOrCreateWallet(driverId, walletProperties.getDefaultCurrency());

        int boundedSize = Math.min(Math.max(size, 1), 100);
        int boundedPage = Math.max(page, 0);

        List<LedgerEntry> entries = entryRepository.findByWalletIdOrderByCreatedAtDesc(
                wallet.getId(),
                PageRequest.of(boundedPage, boundedSize)
        );

        List<LedgerEntryResponse> responses = entries.stream()
                .map(LedgerEntryResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }

    private UUID extractDriverId(Jwt jwt) {
        if (jwt.hasClaim("userId") && jwt.getClaimAsString("userId") != null) {
            return UUID.fromString(jwt.getClaimAsString("userId"));
        }
        return UUID.fromString(jwt.getSubject());
    }
}
