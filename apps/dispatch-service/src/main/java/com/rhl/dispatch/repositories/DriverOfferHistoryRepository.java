package com.rhl.dispatch.repositories;

import com.rhl.dispatch.entities.DriverOfferHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DriverOfferHistoryRepository extends JpaRepository<DriverOfferHistory, UUID> {

    List<DriverOfferHistory> findByOfferIdOrderByCreatedAtAsc(UUID offerId);
}
