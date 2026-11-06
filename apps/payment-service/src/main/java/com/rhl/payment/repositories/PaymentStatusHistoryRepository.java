package com.rhl.payment.repositories;

import com.rhl.payment.entities.PaymentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory, UUID> {

    List<PaymentStatusHistory> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
