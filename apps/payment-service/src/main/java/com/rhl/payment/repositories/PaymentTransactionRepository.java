package com.rhl.payment.repositories;

import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    List<PaymentTransaction> findByTripId(UUID tripId);

    Optional<PaymentTransaction> findFirstByTripIdAndStatus(UUID tripId, PaymentStatus status);

    boolean existsByTripIdAndStatus(UUID tripId, PaymentStatus status);

    boolean existsByTripIdAndStatusIn(UUID tripId, java.util.Collection<PaymentStatus> statuses);

    Optional<PaymentTransaction> findFirstByTripIdAndStatusIn(UUID tripId, java.util.Collection<PaymentStatus> statuses);

    List<PaymentTransaction> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PaymentTransaction p WHERE p.id = :id")
    Optional<PaymentTransaction> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}
