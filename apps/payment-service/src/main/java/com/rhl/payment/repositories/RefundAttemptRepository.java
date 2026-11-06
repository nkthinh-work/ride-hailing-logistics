package com.rhl.payment.repositories;

import com.rhl.payment.entities.RefundAttempt;
import com.rhl.payment.enums.PaymentAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundAttemptRepository extends JpaRepository<RefundAttempt, UUID> {

    List<RefundAttempt> findByRefundIdOrderByAttemptNumberAsc(UUID refundId);

    Optional<RefundAttempt> findByProviderRefundOrderId(String providerRefundOrderId);

    Optional<RefundAttempt> findFirstByRefundIdAndStatusIn(UUID refundId, Collection<PaymentAttemptStatus> statuses);

    boolean existsByRefundIdAndStatusIn(UUID refundId, Collection<PaymentAttemptStatus> statuses);
}
