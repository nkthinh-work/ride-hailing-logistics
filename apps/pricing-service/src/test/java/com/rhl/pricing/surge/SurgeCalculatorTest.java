package com.rhl.pricing.surge;

import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SurgeCalculator Pure Deterministic Tests")
class SurgeCalculatorTest {

    private SurgeCalculator calculator;
    private SurgePolicy defaultPolicy;
    private Instant now;
    private Instant windowStart;

    @BeforeEach
    void setUp() {
        calculator = new SurgeCalculator();
        defaultPolicy = SurgePolicy.defaultDevPolicy();
        now = Instant.parse("2026-10-23T12:00:00Z");
        windowStart = now.minusSeconds(900); // 15 minutes prior
    }

    private SurgeCalculationInput createInput(long demand, long supply) {
        return new SurgeCalculationInput(
                demand,
                supply,
                ServiceType.RIDE,
                VehicleType.CAR_4_SEAT,
                "HANOI",
                windowStart,
                now
        );
    }

    @Nested
    @DisplayName("Edge Cases & Zero Division Protection")
    class EdgeCasesTests {

        @Test
        @DisplayName("Demand = 0 -> minimum multiplier (1.00x), no surge")
        void demandZero_returnsMinMultiplier() {
            SurgeCalculationInput input = createInput(0, 50);
            SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);

            assertThat(result.multiplier()).isEqualByComparingTo("1.00");
            assertThat(result.isSurgeApplied()).isFalse();
            assertThat(result.demandSupplyRatio()).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("Demand = 0 and Supply = 0 -> minimum multiplier (1.00x), no division by zero")
        void demandZeroSupplyZero_returnsMinMultiplierWithoutError() {
            SurgeCalculationInput input = createInput(0, 0);
            SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);

            assertThat(result.multiplier()).isEqualByComparingTo("1.00");
            assertThat(result.isSurgeApplied()).isFalse();
        }

        @Test
        @DisplayName("Supply = 0 and Demand > 0 -> extreme deficit capped at max multiplier (2.50x)")
        void supplyZeroDemandPositive_appliesMaximumCapWithoutDivisionByZero() {
            SurgeCalculationInput input = createInput(25, 0);
            SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);

            assertThat(result.multiplier()).isEqualByComparingTo("2.50");
            assertThat(result.isSurgeApplied()).isTrue();
            assertThat(result.calculationNote()).contains("Zero supply");
        }
    }

    @Nested
    @DisplayName("Demand / Supply Ratio & Multiplier Calculations")
    class RatioCalculationsTests {

        @Test
        @DisplayName("Demand = Supply (ratio 1.00) -> 1.00x, no surge")
        void demandEqualsSupply_returnsOne() {
            SurgeCalculationInput input = createInput(20, 20);
            SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);

            assertThat(result.multiplier()).isEqualByComparingTo("1.00");
            assertThat(result.isSurgeApplied()).isFalse();
            assertThat(result.demandSupplyRatio()).isEqualByComparingTo("1.0000");
        }

        @Test
        @DisplayName("Demand < Supply (over-supply, ratio 0.50) -> 1.00x, never drops below minMultiplier")
        void overSupply_returnsOne() {
            SurgeCalculationInput input = createInput(10, 20);
            SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);

            assertThat(result.multiplier()).isEqualByComparingTo("1.00");
            assertThat(result.isSurgeApplied()).isFalse();
            assertThat(result.demandSupplyRatio()).isEqualByComparingTo("0.5000");
        }

        @Test
        @DisplayName("Demand > Supply: 15 demand, 10 supply (ratio 1.50) -> 1.00 + (0.50 * 0.50) = 1.25x")
        void moderateDemandSurge_calculatesCorrectMultiplier() {
            SurgeCalculationInput input = createInput(15, 10);
            SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);

            assertThat(result.multiplier()).isEqualByComparingTo("1.25");
            assertThat(result.isSurgeApplied()).isTrue();
            assertThat(result.demandSupplyRatio()).isEqualByComparingTo("1.5000");
        }

        @Test
        @DisplayName("Demand > Supply: 20 demand, 10 supply (ratio 2.00) -> 1.00 + (1.00 * 0.50) = 1.50x")
        void higherDemandSurge_calculatesCorrectMultiplier() {
            SurgeCalculationInput input = createInput(20, 10);
            SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);

            assertThat(result.multiplier()).isEqualByComparingTo("1.50");
            assertThat(result.isSurgeApplied()).isTrue();
        }

        @Test
        @DisplayName("Demand > Supply: 40 demand, 10 supply (ratio 4.00) -> 1.00 + (3.00 * 0.50) = 2.50x (reaches cap)")
        void surgeCap_reachesExactMaxMultiplier() {
            SurgeCalculationInput input = createInput(40, 10);
            SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);

            assertThat(result.multiplier()).isEqualByComparingTo("2.50");
            assertThat(result.isSurgeApplied()).isTrue();
        }

        @Test
        @DisplayName("Extreme demand (1,000,000 demand, 10 supply) -> strictly capped at maxMultiplier (2.50x)")
        void extremeDemand_strictlyCappedAtMaxMultiplier() {
            SurgeCalculationInput input = createInput(1_000_000, 10);
            SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);

            assertThat(result.multiplier()).isEqualByComparingTo("2.50");
            assertThat(result.isSurgeApplied()).isTrue();
            assertThat(result.calculationNote()).contains("capped");
        }
    }

    @Nested
    @DisplayName("Determinism & Precision")
    class DeterminismTests {

        @Test
        @DisplayName("1000 iterations with identical input produce bit-exact identical result (FR-PRI-011)")
        void determinism1000Iterations_producesBitExactSameResult() {
            SurgeCalculationInput input = createInput(37, 19);
            SurgeCalculationResult reference = calculator.calculate(input, defaultPolicy, now);

            for (int i = 0; i < 1000; i++) {
                SurgeCalculationResult result = calculator.calculate(input, defaultPolicy, now);
                assertThat(result.multiplier()).isEqualByComparingTo(reference.multiplier());
                assertThat(result.demandSupplyRatio()).isEqualByComparingTo(reference.demandSupplyRatio());
                assertThat(result.isSurgeApplied()).isEqualTo(reference.isSurgeApplied());
            }
        }

        @Test
        @DisplayName("Rounding adheres to scale 2 and RoundingMode.HALF_UP")
        void roundingCheck_halfUpScaleTwo() {
            // Sensitivity = 0.33, ratio = 1.3333, excess = 0.3333 -> raw increase = 0.109989 -> 1.109989 -> rounded to 1.11
            SurgePolicy customPolicy = new SurgePolicy(
                    "P-CUSTOM",
                    "v1",
                    BigDecimal.valueOf(1.00),
                    BigDecimal.valueOf(3.00),
                    BigDecimal.valueOf(1.00),
                    BigDecimal.valueOf(0.33),
                    15,
                    2,
                    RoundingMode.HALF_UP
            );
            SurgeCalculationInput input = createInput(4, 3); // 4/3 = 1.3333
            SurgeCalculationResult result = calculator.calculate(input, customPolicy, now);

            assertThat(result.multiplier().scale()).isEqualTo(2);
            assertThat(result.multiplier()).isEqualByComparingTo("1.11");
        }
    }

    @Nested
    @DisplayName("Input & Policy Invariants Validation")
    class InvariantsTests {

        @Test
        @DisplayName("Negative demand rejected with IllegalArgumentException")
        void negativeDemand_throwsException() {
            assertThatThrownBy(() -> new SurgeCalculationInput(-1, 10, ServiceType.RIDE, VehicleType.MOTORBIKE, "HANOI", windowStart, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("demandCount must not be negative");
        }

        @Test
        @DisplayName("Negative supply rejected with IllegalArgumentException")
        void negativeSupply_throwsException() {
            assertThatThrownBy(() -> new SurgeCalculationInput(10, -5, ServiceType.RIDE, VehicleType.MOTORBIKE, "HANOI", windowStart, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("supplyCount must not be negative");
        }

        @Test
        @DisplayName("windowEnd before windowStart rejected with IllegalArgumentException")
        void invalidWindow_throwsException() {
            assertThatThrownBy(() -> new SurgeCalculationInput(10, 10, ServiceType.RIDE, VehicleType.MOTORBIKE, "HANOI", now, windowStart))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be before windowStart");
        }

        @Test
        @DisplayName("minMultiplier < 1.00 rejected in SurgePolicy")
        void minMultiplierBelowOne_throwsException() {
            assertThatThrownBy(() -> new SurgePolicy("P", "v1", BigDecimal.valueOf(0.99), BigDecimal.valueOf(2.00), BigDecimal.ONE, BigDecimal.ONE, 15, 2, RoundingMode.HALF_UP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minMultiplier must not be less than 1.00");
        }

        @Test
        @DisplayName("maxMultiplier < minMultiplier rejected in SurgePolicy")
        void maxMultiplierBelowMin_throwsException() {
            assertThatThrownBy(() -> new SurgePolicy("P", "v1", BigDecimal.valueOf(2.00), BigDecimal.valueOf(1.50), BigDecimal.ONE, BigDecimal.ONE, 15, 2, RoundingMode.HALF_UP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxMultiplier (1.5) must be >= minMultiplier (2.0)");
        }
    }
}
