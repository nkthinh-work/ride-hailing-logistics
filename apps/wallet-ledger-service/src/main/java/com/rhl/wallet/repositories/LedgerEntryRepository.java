package com.rhl.wallet.repositories;

import com.rhl.wallet.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransaction_Id(UUID transactionId);

    List<LedgerEntry> findByAccountId(UUID accountId);

    List<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(e.amount), 0.00) FROM LedgerEntry e WHERE e.accountId = :accountId AND e.direction = com.rhl.wallet.domain.EntryDirection.DEBIT")
    BigDecimal sumDebitByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT COALESCE(SUM(e.amount), 0.00) FROM LedgerEntry e WHERE e.accountId = :accountId AND e.direction = com.rhl.wallet.domain.EntryDirection.CREDIT")
    BigDecimal sumCreditByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT COALESCE(SUM(e.amount), 0.00) FROM LedgerEntry e WHERE e.walletId = :walletId AND e.direction = com.rhl.wallet.domain.EntryDirection.DEBIT")
    BigDecimal sumDebitByWalletId(@Param("walletId") UUID walletId);

    @Query("SELECT COALESCE(SUM(e.amount), 0.00) FROM LedgerEntry e WHERE e.walletId = :walletId AND e.direction = com.rhl.wallet.domain.EntryDirection.CREDIT")
    BigDecimal sumCreditByWalletId(@Param("walletId") UUID walletId);
}
