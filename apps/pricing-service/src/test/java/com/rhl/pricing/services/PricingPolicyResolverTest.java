package com.rhl.pricing.services;

import com.rhl.pricing.configs.PricingProperties;
import com.rhl.pricing.entities.PricingRule;
import com.rhl.pricing.exceptions.PricingRuleNotFoundException;
import com.rhl.pricing.models.PricingPolicy;
import com.rhl.pricing.models.PricingRuleStatus;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import com.rhl.pricing.repositories.PricingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingPolicyResolverTest {

    @Mock
    private PricingRuleRepository pricingRuleRepository;

    @Mock
    private PricingProperties pricingProperties;

    @InjectMocks
    private PricingPolicyResolver policyResolver;

    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("Resolves effective policy from PostgreSQL when published rule matches timestamp")
    void resolvesFromDatabase_whenRuleExists() {
        PricingRule dbRule = new PricingRule(
                UUID.randomUUID(),
                ServiceType.RIDE,
                VehicleType.MOTORBIKE,
                "DEFAULT",
                3,
                new BigDecimal("14000"),
                new BigDecimal("2.0"),
                new BigDecimal("5000"),
                new BigDecimal("450"),
                new BigDecimal("14000"),
                "VND",
                PricingRuleStatus.PUBLISHED,
                now.minus(2, ChronoUnit.DAYS),
                null,
                now.minus(2, ChronoUnit.DAYS),
                UUID.randomUUID()
        );

        when(pricingRuleRepository.findEffectiveRules(
                eq(ServiceType.RIDE),
                eq(VehicleType.MOTORBIKE),
                eq("DEFAULT"),
                eq(PricingRuleStatus.PUBLISHED),
                any(Instant.class)
        )).thenReturn(List.of(dbRule));

        PricingPolicy resolved = policyResolver.resolveEffectivePolicy(
                ServiceType.RIDE, VehicleType.MOTORBIKE, "DEFAULT", now
        );

        assertThat(resolved).isNotNull();
        assertThat(resolved.ruleVersion()).isEqualTo("v3");
        assertThat(resolved.baseFare()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(resolved.pricePerKm()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(resolved.pricePerMinute()).isEqualByComparingTo(new BigDecimal("450"));
    }

    @Test
    @DisplayName("Falls back to PROVISIONAL_DEV_CONFIG when database has no published rules for DEFAULT region and dev fallback is enabled")
    void fallsBackToDevConfig_whenDatabaseEmptyAndFallbackEnabled() {
        PricingPolicy devPolicy = new PricingPolicy(
                "RULE-DEV-CAR_4_SEAT",
                "DEV-v1.0",
                ServiceType.RIDE,
                VehicleType.CAR_4_SEAT,
                new BigDecimal("20000"),
                new BigDecimal("2.0"),
                new BigDecimal("11000"),
                new BigDecimal("800"),
                new BigDecimal("20000"),
                "VND"
        );

        when(pricingRuleRepository.findEffectiveRules(any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(pricingProperties.isDevFallbackEnabled()).thenReturn(true);
        when(pricingProperties.getPolicy(ServiceType.RIDE, VehicleType.CAR_4_SEAT))
                .thenReturn(Optional.of(devPolicy));

        PricingPolicy resolved = policyResolver.resolveEffectivePolicy(
                ServiceType.RIDE, VehicleType.CAR_4_SEAT, "DEFAULT", now
        );

        assertThat(resolved).isNotNull();
        assertThat(resolved.ruleId()).isEqualTo("RULE-DEV-CAR_4_SEAT");
        assertThat(resolved.baseFare()).isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    @DisplayName("Fails closed in production: throws PricingRuleNotFoundException when DB empty and dev fallback disabled")
    void failsClosed_whenDevFallbackDisabled() {
        when(pricingRuleRepository.findEffectiveRules(any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(pricingProperties.isDevFallbackEnabled()).thenReturn(false);

        assertThatThrownBy(() -> policyResolver.resolveEffectivePolicy(
                ServiceType.RIDE, VehicleType.CAR_4_SEAT, "DEFAULT", now
        )).isInstanceOf(PricingRuleNotFoundException.class)
                .hasMessageContaining("Provisional DEV fallback is disabled (production fail-closed)");
    }

    @Test
    @DisplayName("Throws PricingRuleNotFoundException when non-default region has no DB rules even if dev fallback is enabled")
    void nonDefaultRegion_neverFallsBackToDevConfig() {
        when(pricingRuleRepository.findEffectiveRules(any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> policyResolver.resolveEffectivePolicy(
                ServiceType.DELIVERY, VehicleType.CAR_7_SEAT, "VN-SGN", now
        )).isInstanceOf(PricingRuleNotFoundException.class)
                .hasMessageContaining("No effective pricing rule found");
    }
}
