package com.rhl.pricing.services;

import com.rhl.pricing.calculator.FareCalculator;
import com.rhl.pricing.configs.PricingProperties;
import com.rhl.pricing.dtos.FareQuoteRequest;
import com.rhl.pricing.entities.FareQuote;
import com.rhl.pricing.exceptions.ProductionQuotingBlockedException;
import com.rhl.pricing.exceptions.QuoteAccessDeniedException;
import com.rhl.pricing.exceptions.QuoteExpiredException;
import com.rhl.pricing.exceptions.QuoteNotFoundException;
import com.rhl.pricing.models.*;
import com.rhl.pricing.repositories.FareQuoteRepository;
import com.rhl.pricing.routing.RouteEstimate;
import com.rhl.pricing.routing.RouteEstimateProvider;
import com.rhl.pricing.surge.SurgeCalculationResult;
import com.rhl.pricing.surge.SurgeCalculator;
import com.rhl.pricing.surge.SurgeService;
import com.rhl.pricing.surge.SimulatedDemandSupplyProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service managing Fare Quote creation, customer ownership authorization,
 * server-side TTL expiration, immutable snapshot persistence, dynamic surge integration,
 * and tamper protection (FR-PRI-007..010, FR-PRI-013..018).
 */
@Service
public class FareQuoteService {

    private final RouteEstimateProvider routeEstimateProvider;
    private final PricingPolicyResolver pricingPolicyResolver;
    private final FareCalculator fareCalculator;
    private final FareQuoteRepository fareQuoteRepository;
    private final PricingProperties pricingProperties;
    private final Clock clock;
    private final SurgeService surgeService;

    public FareQuoteService(
            RouteEstimateProvider routeEstimateProvider,
            PricingPolicyResolver pricingPolicyResolver,
            FareCalculator fareCalculator,
            FareQuoteRepository fareQuoteRepository,
            PricingProperties pricingProperties,
            Clock clock) {
        this(
                routeEstimateProvider,
                pricingPolicyResolver,
                fareCalculator,
                fareQuoteRepository,
                pricingProperties,
                clock,
                new SurgeService(new SimulatedDemandSupplyProvider(), new SurgeCalculator(), pricingProperties)
        );
    }

    @Autowired
    public FareQuoteService(
            RouteEstimateProvider routeEstimateProvider,
            PricingPolicyResolver pricingPolicyResolver,
            FareCalculator fareCalculator,
            FareQuoteRepository fareQuoteRepository,
            PricingProperties pricingProperties,
            Clock clock,
            SurgeService surgeService) {
        this.routeEstimateProvider = routeEstimateProvider;
        this.pricingPolicyResolver = pricingPolicyResolver;
        this.fareCalculator = fareCalculator;
        this.fareQuoteRepository = fareQuoteRepository;
        this.pricingProperties = pricingProperties;
        this.clock = clock;
        this.surgeService = Objects.requireNonNull(surgeService, "surgeService must not be null");
    }

    /**
     * Calculates and persists an immutable FareQuote snapshot for the authenticated customer.
     *
     * @param request    quote request containing coordinates, service, and vehicle type
     * @param customerId customer UUID extracted directly from authenticated JWT principal
     * @return persisted immutable FareQuote
     */
    @Transactional
    public FareQuote createQuote(FareQuoteRequest request, UUID customerId) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");

        Instant now = clock.instant();

        // 1. Obtain Route Estimate
        RouteEstimate route = routeEstimateProvider.estimateRoute(
                request.pickupLatitude(),
                request.pickupLongitude(),
                request.dropoffLatitude(),
                request.dropoffLongitude()
        );

        // 2. Production Guard: prevent issuing production fare quotes using simulated routing
        if (route.isSimulated() && !pricingProperties.getQuote().isAllowSimulatedRouting()) {
            throw new ProductionQuotingBlockedException(
                    "Production fare quote cannot be generated from simulated routing provider: " + route.providerName()
            );
        }

        // 3. Resolve Effective Pricing Policy
        String regionId = (request.regionId() != null && !request.regionId().isBlank())
                ? request.regionId().trim().toUpperCase()
                : "DEFAULT";

        PricingPolicy policy = pricingPolicyResolver.resolveEffectivePolicy(
                request.serviceType(),
                request.vehicleType(),
                regionId,
                now
        );

        // 4. Convert route metrics to standard calculation units (km and minutes)
        BigDecimal distanceKm = route.distanceKm();
        BigDecimal durationMinutes = route.durationMinutes();

        // 5. Dynamic Surge Calculation (FR-PRI-007..010, FR-PRI-012)
        SurgeCalculationResult surgeResult = surgeService.calculateSurge(
                request.serviceType(),
                request.vehicleType(),
                regionId,
                now
        );
        BigDecimal surgeMultiplier = surgeResult.multiplier();

        FareCalculationInput calcInput = new FareCalculationInput(
                distanceKm,
                durationMinutes,
                surgeMultiplier,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        FareBreakdown breakdown = fareCalculator.calculate(calcInput, policy);

        // 6. Expiration bounds based on server-side clock
        long ttlSeconds = pricingProperties.getQuote().getTtlSeconds();
        Instant createdAt = now;
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        // 7. Persist immutable snapshot
        FareQuote quote = new FareQuote(
                UUID.randomUUID(),
                customerId,
                request.serviceType(),
                request.vehicleType(),
                regionId,
                request.pickupLatitude(),
                request.pickupLongitude(),
                request.pickupAddress(),
                request.dropoffLatitude(),
                request.dropoffLongitude(),
                request.dropoffAddress(),
                route.distanceMeters(),
                route.durationSeconds(),
                route.providerName(),
                route.isSimulated(),
                policy.ruleId(),
                policy.ruleVersion(),
                breakdown,
                createdAt,
                expiresAt
        );

        return fareQuoteRepository.save(quote);
    }

    /**
     * Retrieves an existing quote by ID, enforcing strict Customer Ownership (FR-PRI-014).
     *
     * @param quoteId    UUID of the quote
     * @param customerId UUID of the requesting authenticated customer
     * @return persisted immutable FareQuote
     * @throws QuoteNotFoundException     if quote does not exist
     * @throws QuoteAccessDeniedException if quote belongs to another customer
     */
    @Transactional(readOnly = true)
    public FareQuote getQuoteById(UUID quoteId, UUID customerId) {
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");

        FareQuote quote = fareQuoteRepository.findById(quoteId)
                .orElseThrow(() -> new QuoteNotFoundException("Fare quote not found: " + quoteId));

        if (!quote.isOwnedBy(customerId)) {
            throw new QuoteAccessDeniedException("Customer does not own fare quote: " + quoteId);
        }

        return quote;
    }

    /**
     * Prepares contract validation for M05 Trip Creation (FR-PRI-015).
     * Verifies that the quote exists, belongs to the customer, has not expired,
     * and matches the requested service and vehicle type.
     */
    @Transactional(readOnly = true)
    public FareQuote validateQuoteForTrip(
            UUID quoteId,
            UUID customerId,
            ServiceType expectedServiceType,
            VehicleType expectedVehicleType) {

        return validateQuoteForTrip(quoteId, customerId, expectedServiceType, expectedVehicleType, true);
    }

    /**
     * Validates an issued quote snapshot for trip booking, verifying customer ownership,
     * non-expiration, service/vehicle consistency, and explicit surge confirmation (FR-PRI-010, FR-PRI-015).
     *
     * @param quoteId quote UUID
     * @param customerId authenticated customer UUID
     * @param expectedServiceType service type expected by trip request
     * @param expectedVehicleType vehicle type expected by trip request
     * @param customerConfirmedSurge whether customer has explicitly acknowledged the surge multiplier
     * @return validated immutable FareQuote
     */
    @Transactional(readOnly = true)
    public FareQuote validateQuoteForTrip(
            UUID quoteId,
            UUID customerId,
            ServiceType expectedServiceType,
            VehicleType expectedVehicleType,
            boolean customerConfirmedSurge) {

        FareQuote quote = getQuoteById(quoteId, customerId);

        Instant now = clock.instant();
        if (quote.isExpired(now)) {
            throw new QuoteExpiredException(String.format(
                    "Fare quote %s expired at %s (current server time %s)",
                    quoteId, quote.getExpiresAt(), now
            ));
        }

        if (expectedServiceType != null && quote.getServiceType() != expectedServiceType) {
            throw new IllegalArgumentException(String.format(
                    "Service type mismatch: quote has %s but trip requested %s",
                    quote.getServiceType(), expectedServiceType
            ));
        }

        if (expectedVehicleType != null && quote.getVehicleType() != expectedVehicleType) {
            throw new IllegalArgumentException(String.format(
                    "Vehicle type mismatch: quote has %s but trip requested %s",
                    quote.getVehicleType(), expectedVehicleType
            ));
        }

        // FR-PRI-010: Explicit customer confirmation required when surge > 1.0
        if (quote.isSurgeApplied() && !customerConfirmedSurge) {
            throw new IllegalArgumentException(String.format(
                    "Customer must explicitly confirm surge multiplier %s before trip booking (FR-PRI-010)",
                    quote.getSurgeMultiplier()
            ));
        }

        return quote;
    }
}
