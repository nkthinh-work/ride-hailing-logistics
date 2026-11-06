package com.rhl.payment.repositories;

import com.rhl.payment.entities.PaymentRefund;
import com.rhl.payment.enums.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, UUID> {

    Optional<PaymentRefund> findByPaymentIdAndIdempotencyKey(UUID paymentId, String idempotencyKey);

    List<PaymentRefund> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    List<PaymentRefund> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<PaymentRefund> findByStatus(RefundStatus status);

    Optional<PaymentRefund> findByProviderRefundOrderId(String providerRefundOrderId);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM PaymentRefund r WHERE r.paymentId = :paymentId AND r.status IN :statuses")
    BigDecimal sumAmountByPaymentIdAndStatusIn(
            @Param("paymentId") UUID paymentId,
            @Param("statuses") Collection<RefundStatus> statuses
    );
}
