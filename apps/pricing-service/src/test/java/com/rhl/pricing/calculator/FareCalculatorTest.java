package com.rhl.pricing.calculator;

import com.rhl.pricing.models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FareCalculatorTest {

    private FareCalculator calculator;
    private PricingPolicy motorbikePolicy;
    private PricingPolicy car4SeatPolicy;
    private PricingPolicy car7SeatPolicy;

    @BeforeEach
    void setUp() {
        calculator = new FareCalculator();

        // MOTORBIKE DEV/TEST policy: 12.000 VND (first 2 km included), 4.500 VND/km, 400 VND/min, min 12.000 VND
        motorbikePolicy = new PricingPolicy(
                "RULE-DEV-MOTORBIKE",
                "DEV-v1.0",
                ServiceType.RIDE,
                VehicleType.MOTORBIKE,
                new BigDecimal("12000"),
                new BigDecimal("2.0"),
                new BigDecimal("4500"),
                new BigDecimal("400"),
                new BigDecimal("12000"),
                "VND"
        );

        // CAR_4_SEAT DEV/TEST policy: 20.000 VND (first 2 km included), 11.000 VND/km, 800 VND/min, min 20.000 VND
        car4SeatPolicy = new PricingPolicy(
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

        // CAR_7_SEAT DEV/TEST policy: 25.000 VND (first 2 km included), 13.000 VND/km, 1.000 VND/min, min 25.000 VND
        car7SeatPolicy = new PricingPolicy(
                "RULE-DEV-CAR_7_SEAT",
                "DEV-v1.0",
                ServiceType.RIDE,
                VehicleType.CAR_7_SEAT,
                new BigDecimal("25000"),
                new BigDecimal("2.0"),
                new BigDecimal("13000"),
                new BigDecimal("1000"),
                new BigDecimal("25000"),
                "VND"
        );
    }

    @Nested
    @DisplayName("Distance Boundary Tests (0 km, 1 km, 2 km, 2.1 km)")
    class DistanceBoundaryTests {

        @Test
        @DisplayName("0 km distance: 0 chargeable km, distance fare 0, base fare applies")
        void zeroDistance_onlyBaseFareApplies() {
            FareCalculationInput input = FareCalculationInput.of(BigDecimal.ZERO, BigDecimal.ZERO);
            FareBreakdown breakdown = calculator.calculate(input, motorbikePolicy);

            assertThat(breakdown.chargeableDistanceKm()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(breakdown.distanceFare()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(breakdown.timeFare()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(breakdown.baseFare()).isEqualByComparingTo(new BigDecimal("12000"));
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("12000"));
        }

        @Test
        @DisplayName("1.0 km distance: within 2 km included, distance fare remains 0")
        void distanceWithinIncludedThreshold_zeroDistanceFare() {
            FareCalculationInput input = FareCalculationInput.of(new BigDecimal("1.0"), BigDecimal.ZERO);
            FareBreakdown breakdown = calculator.calculate(input, motorbikePolicy);

            assertThat(breakdown.chargeableDistanceKm()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(breakdown.distanceFare()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("12000"));
        }

        @Test
        @DisplayName("2.0 km distance: exact boundary of included distance, distance fare is 0")
        void exactIncludedDistanceBoundary_zeroDistanceFare() {
            FareCalculationInput input = FareCalculationInput.of(new BigDecimal("2.0"), BigDecimal.ZERO);
            FareBreakdown breakdown = calculator.calculate(input, motorbikePolicy);

            assertThat(breakdown.chargeableDistanceKm()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(breakdown.distanceFare()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("12000"));
        }

        @Test
        @DisplayName("2.1 km distance: crossing boundary, chargeable distance is 0.1 km (450 VND for Motorbike)")
        void distanceJustAboveBoundary_chargesForExtraFraction() {
            FareCalculationInput input = FareCalculationInput.of(new BigDecimal("2.1"), BigDecimal.ZERO);
            FareBreakdown breakdown = calculator.calculate(input, motorbikePolicy);

            // chargeable = 2.1 - 2.0 = 0.1 km
            assertThat(breakdown.chargeableDistanceKm()).isEqualByComparingTo(new BigDecimal("0.1"));
            // distance fare = 0.1 * 4500 = 450 VND
            assertThat(breakdown.distanceFare()).isEqualByComparingTo(new BigDecimal("450"));
            // total fare = 12000 + 450 = 12450 VND
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("12450"));
        }

        @Test
        @DisplayName("5.0 km distance with 10 mins: standard trip calculation")
        void standardTrip_calculatesCorrectDistanceAndTimeFare() {
            FareCalculationInput input = FareCalculationInput.of(new BigDecimal("5.0"), new BigDecimal("10.0"));
            FareBreakdown breakdown = calculator.calculate(input, motorbikePolicy);

            // chargeable = 5.0 - 2.0 = 3.0 km
            assertThat(breakdown.chargeableDistanceKm()).isEqualByComparingTo(new BigDecimal("3.0"));
            // distance fare = 3.0 * 4500 = 13500 VND
            assertThat(breakdown.distanceFare()).isEqualByComparingTo(new BigDecimal("13500"));
            // time fare = 10.0 * 400 = 4000 VND
            assertThat(breakdown.timeFare()).isEqualByComparingTo(new BigDecimal("4000"));
            // total = 12000 + 13500 + 4000 = 29500 VND
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("29500"));
        }
    }

    @Nested
    @DisplayName("Vehicle Rates Tests under PROVISIONAL_DEV_CONFIG")
    class VehicleRatesTests {

        @Test
        @DisplayName("CAR_4_SEAT: 5.0 km, 15 min trip")
        void car4Seat_fareCalculation() {
            FareCalculationInput input = FareCalculationInput.of(new BigDecimal("5.0"), new BigDecimal("15.0"));
            FareBreakdown breakdown = calculator.calculate(input, car4SeatPolicy);

            // chargeable = 3.0 km * 11000 = 33000 VND
            assertThat(breakdown.distanceFare()).isEqualByComparingTo(new BigDecimal("33000"));
            // time fare = 15 min * 800 = 12000 VND
            assertThat(breakdown.timeFare()).isEqualByComparingTo(new BigDecimal("12000"));
            // standard = 20000 + 33000 + 12000 = 65000 VND
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("65000"));
        }

        @Test
        @DisplayName("CAR_7_SEAT: 10.0 km, 20 min trip")
        void car7Seat_fareCalculation() {
            FareCalculationInput input = FareCalculationInput.of(new BigDecimal("10.0"), new BigDecimal("20.0"));
            FareBreakdown breakdown = calculator.calculate(input, car7SeatPolicy);

            // chargeable = 8.0 km * 13000 = 104000 VND
            assertThat(breakdown.distanceFare()).isEqualByComparingTo(new BigDecimal("104000"));
            // time fare = 20 min * 1000 = 20000 VND
            assertThat(breakdown.timeFare()).isEqualByComparingTo(new BigDecimal("20000"));
            // standard = 25000 + 104000 + 20000 = 149000 VND
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("149000"));
        }
    }

    @Nested
    @DisplayName("Minimum Fare Enforcement Tests")
    class MinimumFareTests {

        @Test
        @DisplayName("Enforces minimum fare when base + distance + time is below minimum fare")
        void enforcesMinimumFare_whenStandardFareIsLower() {
            PricingPolicy policyWithHighMin = new PricingPolicy(
                    "RULE-DEV-HIGH-MIN",
                    "DEV-v1.0",
                    ServiceType.RIDE,
                    VehicleType.CAR_4_SEAT,
                    new BigDecimal("15000"),
                    new BigDecimal("2.0"),
                    new BigDecimal("10000"),
                    new BigDecimal("500"),
                    new BigDecimal("30000"), // minimum fare 30000
                    "VND"
            );

            // 0 km, 0 duration -> standard fare = 15000 < min 30000
            FareCalculationInput input = FareCalculationInput.of(BigDecimal.ZERO, BigDecimal.ZERO);
            FareBreakdown breakdown = calculator.calculate(input, policyWithHighMin);

            assertThat(breakdown.standardFare()).isEqualByComparingTo(new BigDecimal("15000"));
            assertThat(breakdown.minimumFare()).isEqualByComparingTo(new BigDecimal("30000"));
            assertThat(breakdown.preSurgeFare()).isEqualByComparingTo(new BigDecimal("30000"));
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("30000"));
        }
    }

    @Nested
    @DisplayName("Surge Multiplier Tests")
    class SurgeMultiplierTests {

        @Test
        @DisplayName("Surge = 1.0 (no surge): surgedFare equals preSurgeFare")
        void normalMultiplier_noSurgeIncrease() {
            FareCalculationInput input = FareCalculationInput.of(
                    new BigDecimal("5.0"), new BigDecimal("10.0"), BigDecimal.ONE
            );
            FareBreakdown breakdown = calculator.calculate(input, motorbikePolicy);

            assertThat(breakdown.surgeMultiplier()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(breakdown.surgedFare()).isEqualByComparingTo(breakdown.preSurgeFare());
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("29500"));
        }

        @Test
        @DisplayName("Surge = 1.25: correctly multiplies pre-surge fare")
        void moderateSurge_multipliesPreSurgeFare() {
            FareCalculationInput input = FareCalculationInput.of(
                    new BigDecimal("5.0"), new BigDecimal("10.0"), new BigDecimal("1.25")
            );
            FareBreakdown breakdown = calculator.calculate(input, motorbikePolicy);

            // preSurge = 29500 * 1.25 = 36875 VND
            assertThat(breakdown.surgedFare()).isEqualByComparingTo(new BigDecimal("36875"));
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("36875"));
        }

        @Test
        @DisplayName("Surge = 1.50: correctly increases fare by 50%")
        void highSurge_increasesFareByHalf() {
            FareCalculationInput input = FareCalculationInput.of(
                    new BigDecimal("5.0"), new BigDecimal("10.0"), new BigDecimal("1.50")
            );
            FareBreakdown breakdown = calculator.calculate(input, motorbikePolicy);

            // preSurge = 29500 * 1.5 = 44250 VND
            assertThat(breakdown.surgedFare()).isEqualByComparingTo(new BigDecimal("44250"));
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("44250"));
        }

        @Test
        @DisplayName("Surge = 2.00: doubles pre-surge fare")
        void extremeSurge_doublesFare() {
            FareCalculationInput input = FareCalculationInput.of(
                    new BigDecimal("5.0"), new BigDecimal("10.0"), new BigDecimal("2.00")
            );
            FareBreakdown breakdown = calculator.calculate(input, motorbikePolicy);

            // preSurge = 29500 * 2.0 = 59000 VND
            assertThat(breakdown.surgedFare()).isEqualByComparingTo(new BigDecimal("59000"));
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("59000"));
        }
    }

    @Nested
    @DisplayName("Validation and Invariant Tests")
    class InvariantTests {

        @Test
        @DisplayName("Rejects negative distance")
        void negativeDistance_throwsException() {
            assertThatThrownBy(() ->
                    new FareCalculationInput(new BigDecimal("-1.0"), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("distanceKm must not be negative");
        }

        @Test
        @DisplayName("Rejects negative duration")
        void negativeDuration_throwsException() {
            assertThatThrownBy(() ->
                    new FareCalculationInput(BigDecimal.ZERO, new BigDecimal("-5.0"), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("durationMinutes must not be negative");
        }

        @Test
        @DisplayName("Rejects surge multiplier less than 1.0")
        void surgeMultiplierBelowOne_throwsException() {
            assertThatThrownBy(() ->
                    new FareCalculationInput(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.9"), BigDecimal.ZERO, BigDecimal.ZERO)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("surgeMultiplier must be at least 1.0");
        }

        @Test
        @DisplayName("Rejects negative additional fees or discount")
        void negativeFeesOrDiscount_throwsException() {
            assertThatThrownBy(() ->
                    new FareCalculationInput(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("-1000"), BigDecimal.ZERO)
            ).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() ->
                    new FareCalculationInput(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("-500"))
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Large Values & Determinism Tests")
    class PrecisionAndDeterminismTests {

        @Test
        @DisplayName("Long-distance trip (1500 km, 1800 min): BigDecimal calculates without overflow")
        void longDistance_calculatesAccurately() {
            FareCalculationInput input = FareCalculationInput.of(new BigDecimal("1500.0"), new BigDecimal("1800.0"));
            FareBreakdown breakdown = calculator.calculate(input, car4SeatPolicy);

            // chargeable = 1498.0 km * 11000 = 16,478,000 VND
            // time = 1800 min * 800 = 1,440,000 VND
            // base = 20,000 VND
            // total = 17,938,000 VND
            assertThat(breakdown.totalFare()).isEqualByComparingTo(new BigDecimal("17938000"));
        }

        @Test
        @DisplayName("Determinism: 1,000 repeated calculations yield identical results")
        void determinism_yieldsExactSameResultRepeatedly() {
            FareCalculationInput input = FareCalculationInput.of(
                    new BigDecimal("7.3456"), new BigDecimal("18.789"), new BigDecimal("1.35")
            );

            FareBreakdown firstRun = calculator.calculate(input, car4SeatPolicy);

            for (int i = 0; i < 1000; i++) {
                FareBreakdown currentRun = calculator.calculate(input, car4SeatPolicy);
                assertThat(currentRun.totalFare()).isEqualByComparingTo(firstRun.totalFare());
                assertThat(currentRun.distanceFare()).isEqualByComparingTo(firstRun.distanceFare());
                assertThat(currentRun.timeFare()).isEqualByComparingTo(firstRun.timeFare());
            }
        }
    }
}
