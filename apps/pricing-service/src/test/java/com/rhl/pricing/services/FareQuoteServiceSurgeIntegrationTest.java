package com.rhl.pricing.services;

import com.rhl.pricing.calculator.FareCalculator;
import com.rhl.pricing.configs.PricingProperties;
import com.rhl.pricing.dtos.FareQuoteRequest;
import com.rhl.pricing.dtos.FareQuoteResponse;
import com.rhl.pricing.entities.FareQuote;
import com.rhl.pricing.models.PricingPolicy;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import com.rhl.pricing.repositories.FareQuoteRepository;
import com.rhl.pricing.routing.RouteEstimate;
import com.rhl.pricing.routing.RouteEstimateProvider;
import com.rhl.pricing.surge.SimulatedDemandSupplyProvider;
import com.rhl.pricing.surge.SurgeCalculator;
import com.rhl.pricing.surge.SurgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FareQuoteService Dynamic Surge Integration Tests (FR-PRI-007..010, FR-PRI-018)")
class FareQuoteServiceSurgeIntegrationTest {

    @Mock
    private RouteEstimateProvider routeEstimateProvider;

    @Mock
    private PricingPolicyResolver pricingPolicyResolver;

    @Mock
    private FareQuoteRepository fareQuoteRepository;

    private FareCalculator fareCalculator;
    private PricingProperties pricingProperties;
    private SimulatedDemandSupplyProvider demandSupplyProvider;
    private SurgeCalculator surgeCalculator;
    private SurgeService surgeService;
    private Clock fixedClock;
    private Instant now;
    private FareQuoteService fareQuoteService;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        fareCalculator = new FareCalculator();
        pricingProperties = new PricingProperties();
        pricingProperties.getQuote().setTtlSeconds(300);
        pricingProperties.getQuote().setAllowSimulatedRouting(true);

        demandSupplyProvider = new SimulatedDemandSupplyProvider();
        surgeCalculator = new SurgeCalculator();
        surgeService = new SurgeService(demandSupplyProvider, surgeCalculator, pricingProperties);

        now = Instant.parse("2026-10-23T12:00:00Z");
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        fareQuoteService = new FareQuoteService(
                routeEstimateProvider,
                pricingPolicyResolver,
                fareCalculator,
                fareQuoteRepository,
                pricingProperties,
                fixedClock,
                surgeService
        );

        when(routeEstimateProvider.estimateRoute(any(), any(), any(), any()))
                .thenReturn(new RouteEstimate(10_000, 900, "TEST_PROVIDER", false));

        PricingPolicy policy = new PricingPolicy(
                "RULE-DEV-CAR",
                "DEV-v1.0",
                ServiceType.RIDE,
                VehicleType.CAR_4_SEAT,
                BigDecimal.valueOf(20000), // base fare
                BigDecimal.valueOf(2.0),   // included distance
                BigDecimal.valueOf(11000),  // per km
                BigDecimal.valueOf(800),    // per minute
                BigDecimal.valueOf(20000),  // minimum fare
                "VND"
        );
        when(pricingPolicyResolver.resolveEffectivePolicy(any(), any(), any(), any()))
                .thenReturn(policy);

        when(fareQuoteRepository.save(any(FareQuote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private FareQuoteRequest createValidRequest() {
        return new FareQuoteRequest(
                ServiceType.RIDE,
                VehicleType.CAR_4_SEAT,
                BigDecimal.valueOf(21.0285),
                BigDecimal.valueOf(105.8542),
                "Hoan Kiem, Hanoi",
                BigDecimal.valueOf(21.0333),
                BigDecimal.valueOf(105.8167),
                "Ba Dinh, Hanoi",
                "HANOI"
        );
    }

    @Test
    @DisplayName("When surge is disabled (default), quote has 1.00x multiplier and isSurgeApplied = false")
    void surgeDisabled_createsNormalQuote() {
        pricingProperties.getSurge().setEnabled(false);

        FareQuote quote = fareQuoteService.createQuote(createValidRequest(), customerId);

        assertThat(quote.getSurgeMultiplier()).isEqualByComparingTo("1.00");
        assertThat(quote.isSurgeApplied()).isFalse();
        assertThat(quote.getSurgedFare()).isEqualByComparingTo(quote.getPreSurgeFare());

        FareQuoteResponse response = FareQuoteResponse.from(quote, now);
        assertThat(response.isSurgeApplied()).isFalse();
    }

    @Test
    @DisplayName("When surge is enabled and demand > supply, quote snapshot stores surge multiplier and surged fare")
    void surgeEnabled_createsSurgedQuote() {
        pricingProperties.getSurge().setEnabled(true);
        pricingProperties.getSurge().setAllowSimulatedSurge(true);
        demandSupplyProvider.setFixedCounts(30, 15); // ratio 2.0 -> multiplier = 1.00 + (1.00 * 0.5) = 1.50x

        FareQuote quote = fareQuoteService.createQuote(createValidRequest(), customerId);

        assertThat(quote.getSurgeMultiplier()).isEqualByComparingTo("1.50");
        assertThat(quote.isSurgeApplied()).isTrue();
        // preSurgeFare: 20000 + (8km * 11000 = 88000) + (15m * 800 = 12000) = 120000
        // surgedFare: 120000 * 1.50 = 180000
        assertThat(quote.getPreSurgeFare()).isEqualByComparingTo("120000");
        assertThat(quote.getSurgedFare()).isEqualByComparingTo("180000");
        assertThat(quote.getTotalFare()).isEqualByComparingTo("180000");

        FareQuoteResponse response = FareQuoteResponse.from(quote, now);
        assertThat(response.isSurgeApplied()).isTrue();
    }

    @Test
    @DisplayName("Trip validation requires explicit customer confirmation when surge > 1.0 (FR-PRI-010)")
    void validateQuoteForTrip_requiresSurgeConfirmationWhenSurged() {
        pricingProperties.getSurge().setEnabled(true);
        pricingProperties.getSurge().setAllowSimulatedSurge(true);
        demandSupplyProvider.setFixedCounts(30, 15); // multiplier 1.50x

        FareQuote quote = fareQuoteService.createQuote(createValidRequest(), customerId);
        when(fareQuoteRepository.findById(quote.getId())).thenReturn(Optional.of(quote));

        // Attempting to validate without surge confirmation -> rejected
        assertThatThrownBy(() -> fareQuoteService.validateQuoteForTrip(
                quote.getId(),
                customerId,
                ServiceType.RIDE,
                VehicleType.CAR_4_SEAT,
                false // customer did NOT confirm surge
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer must explicitly confirm surge multiplier 1.50 before trip booking (FR-PRI-010)");

        // Validating WITH surge confirmation -> approved
        FareQuote validated = fareQuoteService.validateQuoteForTrip(
                quote.getId(),
                customerId,
                ServiceType.RIDE,
                VehicleType.CAR_4_SEAT,
                true // customer confirmed surge
        );
        assertThat(validated.getId()).isEqualTo(quote.getId());
    }
}
