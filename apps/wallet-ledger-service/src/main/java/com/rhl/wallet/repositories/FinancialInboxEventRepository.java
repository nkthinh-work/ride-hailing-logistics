package com.rhl.wallet.repositories;

import com.rhl.wallet.domain.FinancialInboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinancialInboxEventRepository extends JpaRepository<FinancialInboxEvent, UUID> {

    Optional<FinancialInboxEvent> findByEventId(UUID eventId);

    boolean existsByEventId(UUID eventId);
}
