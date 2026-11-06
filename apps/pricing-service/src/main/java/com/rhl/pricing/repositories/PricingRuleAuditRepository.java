package com.rhl.pricing.repositories;

import com.rhl.pricing.entities.PricingRuleAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PricingRuleAuditRepository extends JpaRepository<PricingRuleAudit, UUID> {

    List<PricingRuleAudit> findByRuleIdOrderByCreatedAtDesc(UUID ruleId);

    List<PricingRuleAudit> findAllByOrderByCreatedAtDesc();
}
