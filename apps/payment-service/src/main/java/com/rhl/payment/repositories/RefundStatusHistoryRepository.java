package com.rhl.payment.repositories;

import com.rhl.payment.entities.RefundStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RefundStatusHistoryRepository extends JpaRepository<RefundStatusHistory, UUID> {

    List<RefundStatusHistory> findByRefundIdOrderByCreatedAtAsc(UUID refundId);
}
