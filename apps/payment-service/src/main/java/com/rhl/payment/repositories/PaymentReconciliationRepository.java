package com.rhl.payment.repositories;

import com.rhl.payment.entities.PaymentReconciliationRecord;
import com.rhl.payment.enums.ReconciliationResolutionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentReconciliationRepository extends JpaRepository<PaymentReconciliationRecord, UUID> {

    List<PaymentReconciliationRecord> findByResolutionStateOrderByCheckedAtDesc(ReconciliationResolutionState resolutionState);

    List<PaymentReconciliationRecord> findByPaymentIdOrderByCheckedAtDesc(UUID paymentId);
}
