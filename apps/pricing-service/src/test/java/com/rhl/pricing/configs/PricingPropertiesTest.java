package com.rhl.pricing.configs;

import com.rhl.pricing.models.PricingPolicy;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PricingPropertiesTest {

    @Autowired
    private PricingProperties pricingProperties;

    @Test
    @DisplayName("Binds PROVISIONAL_DEV_CONFIG from application.yaml correctly for all vehicle types")
    void bindsConfigurationProperly() {
        assertThat(pricingProperties.getDefaultCurrency()).isEqualTo("VND");
        assertThat(pricingProperties.getDefaultRuleVersion()).isEqualTo("DEV-v1.0");

        // MOTORBIKE
        Optional<PricingPolicy> motorbikePolicy = pricingProperties.getPolicy(ServiceType.RIDE, VehicleType.MOTORBIKE);
        assertThat(motorbikePolicy).isPresent();
        assertThat(motorbikePolicy.get().baseFare()).isEqualByComparingTo(new BigDecimal("12000"));
        assertThat(motorbikePolicy.get().includedDistanceKm()).isEqualByComparingTo(new BigDecimal("2.0"));
        assertThat(motorbikePolicy.get().pricePerKm()).isEqualByComparingTo(new BigDecimal("4500"));
        assertThat(motorbikePolicy.get().pricePerMinute()).isEqualByComparingTo(new BigDecimal("400"));
        assertThat(motorbikePolicy.get().minimumFare()).isEqualByComparingTo(new BigDecimal("12000"));

        // CAR_4_SEAT
        Optional<PricingPolicy> car4Policy = pricingProperties.getPolicy(ServiceType.RIDE, VehicleType.CAR_4_SEAT);
        assertThat(car4Policy).isPresent();
        assertThat(car4Policy.get().baseFare()).isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(car4Policy.get().pricePerKm()).isEqualByComparingTo(new BigDecimal("11000"));
        assertThat(car4Policy.get().pricePerMinute()).isEqualByComparingTo(new BigDecimal("800"));
        assertThat(car4Policy.get().minimumFare()).isEqualByComparingTo(new BigDecimal("20000"));

        // CAR_7_SEAT
        Optional<PricingPolicy> car7Policy = pricingProperties.getPolicy(ServiceType.RIDE, VehicleType.CAR_7_SEAT);
        assertThat(car7Policy).isPresent();
        assertThat(car7Policy.get().baseFare()).isEqualByComparingTo(new BigDecimal("25000"));
        assertThat(car7Policy.get().pricePerKm()).isEqualByComparingTo(new BigDecimal("13000"));
        assertThat(car7Policy.get().pricePerMinute()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(car7Policy.get().minimumFare()).isEqualByComparingTo(new BigDecimal("25000"));
    }
}
