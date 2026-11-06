package com.rhl.pricing.repositories;

import com.rhl.pricing.entities.FareQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FareQuoteRepository extends JpaRepository<FareQuote, UUID> {

    List<FareQuote> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
}
