package com.rhl.wallet.repositories;

import com.rhl.wallet.domain.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByBusinessReference(String businessReference);

    Optional<LedgerTransaction> findByExternalEventId(UUID externalEventId);

    boolean existsByBusinessReference(String businessReference);

    boolean existsByExternalEventId(UUID externalEventId);
}
