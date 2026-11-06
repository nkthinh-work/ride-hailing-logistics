package com.rhl.pricing.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteEstimateProviderTest {

    private SimulatedRouteEstimateProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SimulatedRouteEstimateProvider();
    }

    @Test
    @DisplayName("Marks estimate clearly as simulated DEV/TEST fixture")
    void estimate_isMarkedAsSimulated() {
        // Hanoi coordinates: Hoan Kiem (21.0285, 105.8542) to West Lake (21.0535, 105.8200)
        RouteEstimate estimate = provider.estimateRoute(
                new BigDecimal("21.0285"), new BigDecimal("105.8542"),
                new BigDecimal("21.0535"), new BigDecimal("105.8200")
        );

        assertThat(estimate.isSimulated()).isTrue();
        assertThat(estimate.providerName()).isEqualTo(SimulatedRouteEstimateProvider.PROVIDER_NAME);
        assertThat(estimate.distanceMeters()).isGreaterThan(0);
        assertThat(estimate.durationSeconds()).isGreaterThan(0);
        assertThat(estimate.distanceKm()).isGreaterThan(BigDecimal.ZERO);
        assertThat(estimate.durationMinutes()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Zero distance for identical coordinates")
    void identicalCoordinates_zeroDistance() {
        RouteEstimate estimate = provider.estimateRoute(
                new BigDecimal("10.7769"), new BigDecimal("106.7009"),
                new BigDecimal("10.7769"), new BigDecimal("106.7009")
        );

        assertThat(estimate.distanceMeters()).isEqualTo(0);
        assertThat(estimate.durationSeconds()).isEqualTo(0);
        assertThat(estimate.distanceKm()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Rejects out of bounds coordinates")
    void invalidCoordinates_throwException() {
        assertThatThrownBy(() ->
                provider.estimateRoute(
                        new BigDecimal("91.0"), new BigDecimal("100.0"),
                        new BigDecimal("10.0"), new BigDecimal("100.0")
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude out of range");

        assertThatThrownBy(() ->
                provider.estimateRoute(
                        new BigDecimal("10.0"), new BigDecimal("-185.0"),
                        new BigDecimal("10.0"), new BigDecimal("100.0")
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Longitude out of range");
    }
}
