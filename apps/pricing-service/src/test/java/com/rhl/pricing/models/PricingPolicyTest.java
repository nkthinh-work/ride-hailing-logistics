package com.rhl.pricing.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingPolicyTest {

    @Test
    @DisplayName("Creates valid pricing policy successfully")
    void validPolicy_createdSuccessfully() {
        PricingPolicy policy = new PricingPolicy(
                "RULE-001",
                "v1.0",
                ServiceType.RIDE,
                VehicleType.MOTORBIKE,
                new BigDecimal("12000"),
                new BigDecimal("2.0"),
                new BigDecimal("4500"),
                new BigDecimal("400"),
                new BigDecimal("12000"),
                "VND"
        );

        assertThat(policy.ruleId()).isEqualTo("RULE-001");
        assertThat(policy.serviceType()).isEqualTo(ServiceType.RIDE);
        assertThat(policy.vehicleType()).isEqualTo(VehicleType.MOTORBIKE);
        assertThat(policy.currency()).isEqualTo("VND");
    }

    @Test
    @DisplayName("Rejects null or negative pricing parameters")
    void invalidParameters_throwExceptions() {
        assertThatThrownBy(() ->
                new PricingPolicy("RULE-001", "v1.0", ServiceType.RIDE, VehicleType.MOTORBIKE,
                        new BigDecimal("-1000"), new BigDecimal("2.0"), new BigDecimal("4500"),
                        new BigDecimal("400"), new BigDecimal("12000"), "VND")
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseFare must not be negative");

        assertThatThrownBy(() ->
                new PricingPolicy("RULE-001", "v1.0", ServiceType.RIDE, VehicleType.MOTORBIKE,
                        new BigDecimal("12000"), new BigDecimal("-1.0"), new BigDecimal("4500"),
                        new BigDecimal("400"), new BigDecimal("12000"), "VND")
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("includedDistanceKm must not be negative");

        assertThatThrownBy(() ->
                new PricingPolicy("RULE-001", "v1.0", ServiceType.RIDE, VehicleType.MOTORBIKE,
                        new BigDecimal("12000"), new BigDecimal("2.0"), new BigDecimal("4500"),
                        new BigDecimal("400"), new BigDecimal("12000"), "   ")
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency must not be blank");
    }
}
