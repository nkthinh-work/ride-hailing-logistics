package com.rhl.pricing.services;

import com.rhl.pricing.calculator.FareCalculator;
import com.rhl.pricing.configs.PricingProperties;
import com.rhl.pricing.dtos.FareQuoteRequest;
import com.rhl.pricing.entities.FareQuote;
import com.rhl.pricing.exceptions.ProductionQuotingBlockedException;
import com.rhl.pricing.exceptions.QuoteAccessDeniedException;
import com.rhl.pricing.exceptions.QuoteExpiredException;
import com.rhl.pricing.exceptions.QuoteNotFoundException;
import com.rhl.pricing.models.PricingPolicy;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import com.rhl.pricing.repositories.FareQuoteRepository;
import com.rhl.pricing.routing.RouteEstimate;
import com.rhl.pricing.routing.RouteEstimateProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FareQuoteService Tests")
class FareQuoteServiceTest {

    @Mock
    private RouteEstimateProvider routeEstimateProvider;

    @Mock
    private PricingPolicyResolver pricingPolicyResolver;

    @Mock
    private FareQuoteRepository fareQuoteRepository;

    private FareCalculator fareCalculator;
    private PricingProperties pricingProperties;
    private Clock fixedClock;
    private Instant baseTime;
    private FareQuoteService fareQuoteService;

    @BeforeEach
    void setUp() {
        fareCalculator = new FareCalculator();
        pricingProperties = new PricingProperties();
        pricingProperties.getQuote().setTtlSeconds(300); // 5 minutes
        pricingProperties.getQuote().setAllowSimulatedRouting(true);

        baseTime = Instant.parse("2026-10-23T12:00:00Z");
        fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC);

        fareQuoteService = new FareQuoteService(
                routeEstimateProvider,
                pricingPolicyResolver,
                fareCalculator,
                fareQuoteRepository,
                pricingProperties,
                fixedClock
        );
    }

    private PricingPolicy samplePolicy() {
        return new PricingPolicy(
                "RULE-001",
                "v1",
                ServiceType.RIDE,
                VehicleType.MOTORBIKE,
                new BigDecimal("12000"),
                new BigDecimal("2.0"),
                new BigDecimal("4500"),
                new BigDecimal("400"),
                new BigDecimal("12000"),
                "VND"
        );
    }

    private FareQuoteRequest sampleRequest() {
        return new FareQuoteRequest(
                ServiceType.RIDE,
                VehicleType.MOTORBIKE,
                new BigDecimal("10.7769"),
                new BigDecimal("106.7009"),
                "Saigon Opera House",
                new BigDecimal("10.7725"),
                new BigDecimal("106.6980"),
                "Ben Thanh Market",
                "DEFAULT"
        );
    }

    @Nested
    @DisplayName("Create Quote Tests")
    class CreateQuoteTests {

        @Test
        @DisplayName("Should successfully calculate and persist immutable fare quote")
        void shouldCreateAndPersistQuote() {
            UUID customerId = UUID.randomUUID();
            FareQuoteRequest request = sampleRequest();

            when(routeEstimateProvider.estimateRoute(any(), any(), any(), any()))
                    .thenReturn(new RouteEstimate(5000L, 600L, "SIMULATED_DEV_FIXTURE", true));

            when(pricingPolicyResolver.resolveEffectivePolicy(eq(ServiceType.RIDE), eq(VehicleType.MOTORBIKE), eq("DEFAULT"), any()))
                    .thenReturn(samplePolicy());

            when(fareQuoteRepository.save(any(FareQuote.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            FareQuote quote = fareQuoteService.createQuote(request, customerId);

            assertThat(quote).isNotNull();
            assertThat(quote.getCustomerId()).isEqualTo(customerId);
            assertThat(quote.getServiceType()).isEqualTo(ServiceType.RIDE);
            assertThat(quote.getVehicleType()).isEqualTo(VehicleType.MOTORBIKE);
            assertThat(quote.getDistanceMeters()).isEqualTo(5000L);
            assertThat(quote.getDurationSeconds()).isEqualTo(600L);
            assertThat(quote.getRouteProvider()).isEqualTo("SIMULATED_DEV_FIXTURE");
            assertThat(quote.isSimulated()).isTrue();
            assertThat(quote.getPricingRuleId()).isEqualTo("RULE-001");
            assertThat(quote.getPricingRuleVersion()).isEqualTo("v1");

            // Base 12000 (2km included) + 3km * 4500 (13500) + 10min * 400 (4000) = 29500 VND
            assertThat(quote.getTotalFare()).isEqualByComparingTo("29500");
            assertThat(quote.getCurrency()).isEqualTo("VND");
            assertThat(quote.getCreatedAt()).isEqualTo(baseTime);
            assertThat(quote.getExpiresAt()).isEqualTo(baseTime.plusSeconds(300));

            verify(fareQuoteRepository, times(1)).save(any(FareQuote.class));
        }

        @Test
        @DisplayName("Should reject simulated routing when allowSimulatedRouting is disabled (Production Safety)")
        void shouldRejectSimulatedRoutingWhenDisabled() {
            pricingProperties.getQuote().setAllowSimulatedRouting(false);

            when(routeEstimateProvider.estimateRoute(any(), any(), any(), any()))
                    .thenReturn(new RouteEstimate(5000L, 600L, "SIMULATED_DEV_FIXTURE", true));

            assertThatThrownBy(() -> fareQuoteService.createQuote(sampleRequest(), UUID.randomUUID()))
                    .isInstanceOf(ProductionQuotingBlockedException.class)
                    .hasMessageContaining("simulated routing provider");

            verify(fareQuoteRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Customer Ownership Tests")
    class CustomerOwnershipTests {

        @Test
        @DisplayName("Should return quote when authenticated customer is the owner")
        void shouldReturnQuoteForOwner() {
            UUID ownerId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();

            FareQuote quote = new FareQuote(
                    quoteId,
                    ownerId,
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("10.7769"),
                    new BigDecimal("106.7009"),
                    "Pickup",
                    new BigDecimal("10.7725"),
                    new BigDecimal("106.6980"),
                    "Dropoff",
                    5000L,
                    600L,
                    "OSRM",
                    false,
                    "RULE-001",
                    "v1",
                    fareCalculator.calculate(
                            new com.rhl.pricing.models.FareCalculationInput(
                                    BigDecimal.valueOf(5), BigDecimal.valueOf(10), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO
                            ),
                            samplePolicy()
                    ),
                    baseTime,
                    baseTime.plusSeconds(300)
            );

            when(fareQuoteRepository.findById(quoteId)).thenReturn(Optional.of(quote));

            FareQuote retrieved = fareQuoteService.getQuoteById(quoteId, ownerId);

            assertThat(retrieved).isNotNull();
            assertThat(retrieved.getId()).isEqualTo(quoteId);
            assertThat(retrieved.getCustomerId()).isEqualTo(ownerId);
        }

        @Test
        @DisplayName("Should throw QuoteAccessDeniedException when customer A accesses quote of customer B")
        void shouldRejectNonOwner() {
            UUID ownerId = UUID.randomUUID();
            UUID strangerId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();

            FareQuote quote = new FareQuote(
                    quoteId,
                    ownerId,
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("10.7769"),
                    new BigDecimal("106.7009"),
                    "Pickup",
                    new BigDecimal("10.7725"),
                    new BigDecimal("106.6980"),
                    "Dropoff",
                    5000L,
                    600L,
                    "OSRM",
                    false,
                    "RULE-001",
                    "v1",
                    fareCalculator.calculate(
                            new com.rhl.pricing.models.FareCalculationInput(
                                    BigDecimal.valueOf(5), BigDecimal.valueOf(10), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO
                            ),
                            samplePolicy()
                    ),
                    baseTime,
                    baseTime.plusSeconds(300)
            );

            when(fareQuoteRepository.findById(quoteId)).thenReturn(Optional.of(quote));

            assertThatThrownBy(() -> fareQuoteService.getQuoteById(quoteId, strangerId))
                    .isInstanceOf(QuoteAccessDeniedException.class)
                    .hasMessageContaining("Customer does not own fare quote");
        }

        @Test
        @DisplayName("Should throw QuoteNotFoundException when quote does not exist")
        void shouldThrowWhenQuoteNotFound() {
            UUID nonExistent = UUID.randomUUID();
            when(fareQuoteRepository.findById(nonExistent)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> fareQuoteService.getQuoteById(nonExistent, UUID.randomUUID()))
                    .isInstanceOf(QuoteNotFoundException.class)
                    .hasMessageContaining("Fare quote not found");
        }
    }

    @Nested
    @DisplayName("Trip Validation and Expiration Tests")
    class TripValidationTests {

        @Test
        @DisplayName("Should successfully validate quote when active and attributes match")
        void shouldValidateQuoteForTrip() {
            UUID customerId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();

            FareQuote quote = new FareQuote(
                    quoteId,
                    customerId,
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("10.7769"),
                    new BigDecimal("106.7009"),
                    "Pickup",
                    new BigDecimal("10.7725"),
                    new BigDecimal("106.6980"),
                    "Dropoff",
                    5000L,
                    600L,
                    "OSRM",
                    false,
                    "RULE-001",
                    "v1",
                    fareCalculator.calculate(
                            new com.rhl.pricing.models.FareCalculationInput(
                                    BigDecimal.valueOf(5), BigDecimal.valueOf(10), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO
                            ),
                            samplePolicy()
                    ),
                    baseTime,
                    baseTime.plusSeconds(300)
            );

            when(fareQuoteRepository.findById(quoteId)).thenReturn(Optional.of(quote));

            FareQuote validated = fareQuoteService.validateQuoteForTrip(
                    quoteId, customerId, ServiceType.RIDE, VehicleType.MOTORBIKE
            );

            assertThat(validated).isNotNull();
        }

        @Test
        @DisplayName("Should reject quote with QuoteExpiredException when clock passes expiresAt")
        void shouldRejectExpiredQuote() {
            UUID customerId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();

            FareQuote quote = new FareQuote(
                    quoteId,
                    customerId,
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("10.7769"),
                    new BigDecimal("106.7009"),
                    "Pickup",
                    new BigDecimal("10.7725"),
                    new BigDecimal("106.6980"),
                    "Dropoff",
                    5000L,
                    600L,
                    "OSRM",
                    false,
                    "RULE-001",
                    "v1",
                    fareCalculator.calculate(
                            new com.rhl.pricing.models.FareCalculationInput(
                                    BigDecimal.valueOf(5), BigDecimal.valueOf(10), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO
                            ),
                            samplePolicy()
                    ),
                    baseTime,
                    baseTime.plusSeconds(300) // expires at baseTime + 300s
            );

            when(fareQuoteRepository.findById(quoteId)).thenReturn(Optional.of(quote));

            // Advance clock by 301 seconds
            Clock advancedClock = Clock.fixed(baseTime.plusSeconds(301), ZoneOffset.UTC);
            FareQuoteService advancedService = new FareQuoteService(
                    routeEstimateProvider,
                    pricingPolicyResolver,
                    fareCalculator,
                    fareQuoteRepository,
                    pricingProperties,
                    advancedClock
            );

            assertThatThrownBy(() -> advancedService.validateQuoteForTrip(
                    quoteId, customerId, ServiceType.RIDE, VehicleType.MOTORBIKE
            )).isInstanceOf(QuoteExpiredException.class)
                    .hasMessageContaining("expired at");
        }

        @Test
        @DisplayName("Should reject quote if requested vehicle type differs from quote")
        void shouldRejectVehicleTypeMismatch() {
            UUID customerId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();

            FareQuote quote = new FareQuote(
                    quoteId,
                    customerId,
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("10.7769"),
                    new BigDecimal("106.7009"),
                    "Pickup",
                    new BigDecimal("10.7725"),
                    new BigDecimal("106.6980"),
                    "Dropoff",
                    5000L,
                    600L,
                    "OSRM",
                    false,
                    "RULE-001",
                    "v1",
                    fareCalculator.calculate(
                            new com.rhl.pricing.models.FareCalculationInput(
                                    BigDecimal.valueOf(5), BigDecimal.valueOf(10), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO
                            ),
                            samplePolicy()
                    ),
                    baseTime,
                    baseTime.plusSeconds(300)
            );

            when(fareQuoteRepository.findById(quoteId)).thenReturn(Optional.of(quote));

            assertThatThrownBy(() -> fareQuoteService.validateQuoteForTrip(
                    quoteId, customerId, ServiceType.RIDE, VehicleType.CAR_4_SEAT
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Vehicle type mismatch");
        }
    }
}
