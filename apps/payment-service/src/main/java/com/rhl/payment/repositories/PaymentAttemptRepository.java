package com.rhl.payment.repositories;

import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.enums.PaymentAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByProviderAndProviderOrderId(String provider, String providerOrderId);

    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    List<PaymentAttempt> findByPaymentIdOrderByAttemptNumberAsc(UUID paymentId);

    Optional<PaymentAttempt> findFirstByPaymentIdAndStatusIn(UUID paymentId, Collection<PaymentAttemptStatus> statuses);

    boolean existsByPaymentIdAndStatusIn(UUID paymentId, Collection<PaymentAttemptStatus> statuses);

    List<PaymentAttempt> findByStatusAndCreatedAtBefore(PaymentAttemptStatus status, Instant cutoff);

    List<PaymentAttempt> findByStatusInAndCreatedAtBefore(Collection<PaymentAttemptStatus> statuses, Instant cutoff);
}
