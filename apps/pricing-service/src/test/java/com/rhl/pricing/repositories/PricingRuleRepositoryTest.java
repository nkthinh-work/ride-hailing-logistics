package com.rhl.pricing.repositories;

import com.rhl.pricing.entities.PricingRule;
import com.rhl.pricing.models.PricingRuleStatus;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PricingRuleRepositoryTest {

    @Autowired
    private PricingRuleRepository repository;

    private Instant baseTime;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        adminId = UUID.randomUUID();
    }

    @Test
    @DisplayName("findEffectiveRules: returns active published rule matching the timestamp")
    void findEffectiveRules_matchesTimestamp() {
        // Rule v1: active from baseTime - 10 days to baseTime - 2 days
        PricingRule v1 = new PricingRule(
                UUID.randomUUID(), ServiceType.RIDE, VehicleType.MOTORBIKE, "DEFAULT", 1,
                new BigDecimal("10000"), new BigDecimal("2.0"), new BigDecimal("4000"),
                new BigDecimal("350"), new BigDecimal("10000"), "VND",
                PricingRuleStatus.PUBLISHED, baseTime.minus(10, ChronoUnit.DAYS), baseTime.minus(2, ChronoUnit.DAYS),
                baseTime.minus(10, ChronoUnit.DAYS), adminId
        );
        repository.save(v1);

        // Rule v2: active from baseTime - 2 days to infinity (null)
        PricingRule v2 = new PricingRule(
                UUID.randomUUID(), ServiceType.RIDE, VehicleType.MOTORBIKE, "DEFAULT", 2,
                new BigDecimal("12000"), new BigDecimal("2.0"), new BigDecimal("4500"),
                new BigDecimal("400"), new BigDecimal("12000"), "VND",
                PricingRuleStatus.PUBLISHED, baseTime.minus(2, ChronoUnit.DAYS), null,
                baseTime.minus(2, ChronoUnit.DAYS), adminId
        );
        repository.save(v2);

        // Historical query: 5 days ago should resolve v1
        List<PricingRule> pastRules = repository.findEffectiveRules(
                ServiceType.RIDE, VehicleType.MOTORBIKE, "DEFAULT", PricingRuleStatus.PUBLISHED,
                baseTime.minus(5, ChronoUnit.DAYS)
        );
        assertThat(pastRules).hasSize(1);
        assertThat(pastRules.getFirst().getVersion()).isEqualTo(1);

        // Current query: now should resolve v2
        List<PricingRule> currentRules = repository.findEffectiveRules(
                ServiceType.RIDE, VehicleType.MOTORBIKE, "DEFAULT", PricingRuleStatus.PUBLISHED,
                baseTime
        );
        assertThat(currentRules).hasSize(1);
        assertThat(currentRules.getFirst().getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("findConflictingRules: detects interval overlaps accurately")
    void findConflictingRules_detectsOverlap() {
        // Existing rule: Nov 10 to Nov 20
        Instant start = baseTime.plus(10, ChronoUnit.DAYS);
        Instant end = baseTime.plus(20, ChronoUnit.DAYS);
        PricingRule rule = new PricingRule(
                UUID.randomUUID(), ServiceType.RIDE, VehicleType.CAR_4_SEAT, "VN-HAN", 1,
                new BigDecimal("20000"), new BigDecimal("2.0"), new BigDecimal("11000"),
                new BigDecimal("800"), new BigDecimal("20000"), "VND",
                PricingRuleStatus.PUBLISHED, start, end, baseTime, adminId
        );
        repository.save(rule);

        // Case 1: Overlaps (Nov 15 to Nov 25)
        List<PricingRule> conflicts1 = repository.findConflictingRules(
                ServiceType.RIDE, VehicleType.CAR_4_SEAT, "VN-HAN",
                baseTime.plus(15, ChronoUnit.DAYS), baseTime.plus(25, ChronoUnit.DAYS), null
        );
        assertThat(conflicts1).hasSize(1);

        // Case 2: Adjacent interval (Nov 20 to Nov 30) -> NO overlap (end exclusive)
        List<PricingRule> conflicts2 = repository.findConflictingRules(
                ServiceType.RIDE, VehicleType.CAR_4_SEAT, "VN-HAN",
                end, baseTime.plus(30, ChronoUnit.DAYS), null
        );
        assertThat(conflicts2).isEmpty();

        // Case 3: Different region -> NO overlap
        List<PricingRule> conflicts3 = repository.findConflictingRules(
                ServiceType.RIDE, VehicleType.CAR_4_SEAT, "VN-SGN",
                baseTime.plus(15, ChronoUnit.DAYS), baseTime.plus(25, ChronoUnit.DAYS), null
        );
        assertThat(conflicts3).isEmpty();

        // Case 4: Different vehicle type -> NO overlap
        List<PricingRule> conflicts4 = repository.findConflictingRules(
                ServiceType.RIDE, VehicleType.CAR_7_SEAT, "VN-HAN",
                baseTime.plus(15, ChronoUnit.DAYS), baseTime.plus(25, ChronoUnit.DAYS), null
        );
        assertThat(conflicts4).isEmpty();
    }

    @Test
    @DisplayName("findMaxVersion: returns highest version per scope")
    void findMaxVersion_returnsHighest() {
        assertThat(repository.findMaxVersion(ServiceType.RIDE, VehicleType.CAR_7_SEAT, "DEFAULT")).isEqualTo(0);

        PricingRule v1 = new PricingRule(
                UUID.randomUUID(), ServiceType.RIDE, VehicleType.CAR_7_SEAT, "DEFAULT", 1,
                new BigDecimal("25000"), new BigDecimal("2.0"), new BigDecimal("13000"),
                new BigDecimal("1000"), new BigDecimal("25000"), "VND",
                PricingRuleStatus.PUBLISHED, baseTime, null, baseTime, adminId
        );
        repository.save(v1);

        assertThat(repository.findMaxVersion(ServiceType.RIDE, VehicleType.CAR_7_SEAT, "DEFAULT")).isEqualTo(1);
    }
}
