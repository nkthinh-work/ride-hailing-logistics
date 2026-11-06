package com.rhl.pricing.surge;

import com.rhl.pricing.configs.PricingProperties;
import com.rhl.pricing.exceptions.ProductionQuotingBlockedException;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SurgeService Orchestration Tests")
class SurgeServiceTest {

    private SimulatedDemandSupplyProvider simulatedProvider;
    private SurgeCalculator surgeCalculator;
    private PricingProperties pricingProperties;
    private SurgeService surgeService;
    private Instant now;

    @BeforeEach
    void setUp() {
        simulatedProvider = new SimulatedDemandSupplyProvider();
        surgeCalculator = new SurgeCalculator();
        pricingProperties = new PricingProperties();
        pricingProperties.getSurge().setEnabled(false); // default disabled
        pricingProperties.getSurge().setAllowSimulatedSurge(true);

        surgeService = new SurgeService(simulatedProvider, surgeCalculator, pricingProperties);
        now = Instant.parse("2026-10-23T12:00:00Z");
    }

    @Test
    @DisplayName("When surge is disabled by policy (default), returns 1.00x no-surge")
    void surgeDisabled_returnsOneMultiplier() {
        pricingProperties.getSurge().setEnabled(false);

        SurgeCalculationResult result = surgeService.calculateSurge(
                ServiceType.RIDE,
                VehicleType.CAR_4_SEAT,
                "HANOI",
                now
        );

        assertThat(result.multiplier()).isEqualByComparingTo("1.00");
        assertThat(result.isSurgeApplied()).isFalse();
    }

    @Test
    @DisplayName("When surge is enabled with simulated provider, computes surge correctly")
    void surgeEnabled_computesSurge() {
        pricingProperties.getSurge().setEnabled(true);
        pricingProperties.getSurge().setAllowSimulatedSurge(true);
        simulatedProvider.setFixedCounts(30, 15); // ratio = 2.0 -> 1.0 + (1.0 * 0.5) = 1.50x

        SurgeCalculationResult result = surgeService.calculateSurge(
                ServiceType.RIDE,
                VehicleType.CAR_4_SEAT,
                "HANOI",
                now
        );

        assertThat(result.multiplier()).isEqualByComparingTo("1.50");
        assertThat(result.isSurgeApplied()).isTrue();
        assertThat(result.demandSupplyRatio()).isEqualByComparingTo("2.0000");
    }

    @Test
    @DisplayName("When allowSimulatedSurge is false and provider is simulated, throws ProductionQuotingBlockedException")
    void simulatedSurgeDisallowed_throwsException() {
        pricingProperties.getSurge().setEnabled(true);
        pricingProperties.getSurge().setAllowSimulatedSurge(false); // simulate production mode

        assertThatThrownBy(() -> surgeService.calculateSurge(ServiceType.RIDE, VehicleType.CAR_4_SEAT, "HANOI", now))
                .isInstanceOf(ProductionQuotingBlockedException.class)
                .hasMessageContaining("Production surge quoting blocked")
                .hasMessageContaining("SIMULATED_DEMAND_SUPPLY_PROVIDER");
    }
}
