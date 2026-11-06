package com.rhl.wallet.repositories;

import com.rhl.wallet.domain.AccountOwnerType;
import com.rhl.wallet.domain.LedgerAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByAccountCode(String accountCode);

    List<LedgerAccount> findByOwnerTypeAndOwnerId(AccountOwnerType ownerType, UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LedgerAccount a WHERE a.id = :id")
    Optional<LedgerAccount> findByIdForUpdate(@Param("id") UUID id);
}
