package com.rhl.trip.services;

import com.rhl.trip.clients.PricingQuoteClient;
import com.rhl.trip.dtos.CreateTripRequest;
import com.rhl.trip.dtos.QuoteValidationClientRequest;
import com.rhl.trip.dtos.QuoteValidationClientResponse;
import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.ActiveTripConflictException;
import com.rhl.trip.exceptions.IdempotencyConflictException;
import com.rhl.trip.exceptions.InvalidQuoteException;
import com.rhl.trip.exceptions.QuoteReplayConflictException;
import com.rhl.trip.exceptions.TripAccessDeniedException;
import com.rhl.trip.exceptions.TripNotFoundException;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Core Trip Booking Service (M05 Group 2).
 * Handles idempotency validation, quote replay prevention, customer active trip concurrency limits,
 * cross-service quote validation, and atomic trip creation.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final TripRepository tripRepository;
    private final PricingQuoteClient pricingQuoteClient;
    private final AtomicTripCreator atomicTripCreator;

    public BookingService(
            TripRepository tripRepository,
            PricingQuoteClient pricingQuoteClient,
            AtomicTripCreator atomicTripCreator) {
        this.tripRepository = tripRepository;
        this.pricingQuoteClient = pricingQuoteClient;
        this.atomicTripCreator = atomicTripCreator;
    }

    /**
     * Creates a new trip atomically or replays existing trip for idempotent retries.
     *
     * @param customerId authenticated customer user ID from JWT Subject
     * @param request validated booking request payload
     * @return created or replayed TripResponse
     */
    public TripResponse bookTrip(UUID customerId, CreateTripRequest request) {
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(request, "request is required");

        // 1. Idempotency pre-check: Return existing trip if payload matches or reject if payload diverges
        Optional<Trip> existingByIdempotency = tripRepository.findByCustomerIdAndIdempotencyKey(
                customerId, request.idempotencyKey()
        );
        if (existingByIdempotency.isPresent()) {
            return validateAndReturnExisting(existingByIdempotency.get(), request);
        }

        // 2. Quote Replay pre-check: A quote cannot be used to book multiple trips across any client
        if (tripRepository.existsByQuoteId(request.quoteId())) {
            throw new QuoteReplayConflictException(request.quoteId());
        }

        // 3. Customer Active Trip pre-check (FR-TRIP-007)
        List<TripStatus> activeStatuses = Arrays.stream(TripStatus.values())
                .filter(TripStatus::isActive)
                .toList();
        if (tripRepository.existsByCustomerIdAndStatusIn(customerId, activeStatuses)) {
            throw new ActiveTripConflictException(customerId);
        }

        // 4. Cross-service Quote Validation (HTTP call outside DB transaction)
        QuoteValidationClientRequest validationRequest = new QuoteValidationClientRequest(
                request.quoteId(),
                customerId,
                request.serviceType(),
                request.vehicleType(),
                request.customerConfirmedSurge()
        );

        QuoteValidationClientResponse quoteSnapshot = pricingQuoteClient.validateQuote(validationRequest);

        // Optional cross-validation of coordinates if supplied by client
        if (request.pickupLatitude() != null && request.pickupLongitude() != null) {
            if (quoteSnapshot.pickupLatitude().compareTo(request.pickupLatitude()) != 0
                    || quoteSnapshot.pickupLongitude().compareTo(request.pickupLongitude()) != 0) {
                throw new InvalidQuoteException("Trip pickup coordinates do not match issued fare quote");
            }
        }
        if (request.dropoffLatitude() != null && request.dropoffLongitude() != null) {
            if (quoteSnapshot.dropoffLatitude().compareTo(request.dropoffLatitude()) != 0
                    || quoteSnapshot.dropoffLongitude().compareTo(request.dropoffLongitude()) != 0) {
                throw new InvalidQuoteException("Trip dropoff coordinates do not match issued fare quote");
            }
        }

        // 5. Atomic persist in local DB transaction with database constraint protection
        try {
            Trip createdTrip = atomicTripCreator.createTripInTransaction(customerId, request, quoteSnapshot);
            log.info("Trip created successfully: tripId={}, customerId={}, quoteId={}, status={}",
                    createdTrip.getId(), customerId, request.quoteId(), createdTrip.getStatus());
            return TripResponse.from(createdTrip);
        } catch (DataIntegrityViolationException ex) {
            return handleConstraintViolation(ex, customerId, request);
        }
    }

    /**
     * Fetches trip details for authenticated customer.
     */
    @Transactional(readOnly = true)
    public TripResponse getTripById(UUID tripId, UUID customerId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        if (!trip.getCustomerId().equals(customerId)) {
            throw new TripAccessDeniedException(tripId, customerId);
        }

        return TripResponse.from(trip);
    }

    private TripResponse validateAndReturnExisting(Trip existingTrip, CreateTripRequest request) {
        boolean basicMatch = existingTrip.getQuoteId().equals(request.quoteId())
                && existingTrip.getServiceType() == request.serviceType()
                && existingTrip.getVehicleType().equalsIgnoreCase(request.vehicleType());

        // Semantic equality for surge confirmation:
        // If the trip was created with surge (> 1.0), retry cannot negate surge confirmation
        boolean surgeMatch = existingTrip.getSurgeMultiplier().compareTo(BigDecimal.ONE) <= 0
                || request.customerConfirmedSurge();

        // Semantic equality for coordinates if provided by client in retry request
        boolean coordinatesMatch = true;
        if (request.pickupLatitude() != null && existingTrip.getPickupLatitude().compareTo(request.pickupLatitude()) != 0) {
            coordinatesMatch = false;
        }
        if (request.pickupLongitude() != null && existingTrip.getPickupLongitude().compareTo(request.pickupLongitude()) != 0) {
            coordinatesMatch = false;
        }
        if (request.dropoffLatitude() != null && existingTrip.getDropoffLatitude().compareTo(request.dropoffLatitude()) != 0) {
            coordinatesMatch = false;
        }
        if (request.dropoffLongitude() != null && existingTrip.getDropoffLongitude().compareTo(request.dropoffLongitude()) != 0) {
            coordinatesMatch = false;
        }

        if (basicMatch && surgeMatch && coordinatesMatch) {
            log.info("Idempotent retry detected for customerId={}, idempotencyKey={}. Returning existing tripId={}",
                    existingTrip.getCustomerId(), request.idempotencyKey(), existingTrip.getId());
            return TripResponse.from(existingTrip);
        }

        log.warn("Idempotency conflict: key {} used with different payload (existing quoteId={}, request quoteId={})",
                request.idempotencyKey(), existingTrip.getQuoteId(), request.quoteId());
        throw new IdempotencyConflictException(request.idempotencyKey());
    }

    private TripResponse handleConstraintViolation(
            DataIntegrityViolationException ex,
            UUID customerId,
            CreateTripRequest request) {

        String message = ex.getMessage() != null ? ex.getMessage() : "";
        Throwable rootCause = ex.getRootCause();
        if (rootCause != null && rootCause.getMessage() != null) {
            message = message + " " + rootCause.getMessage();
        }

        if (message.contains("uk_trips_quote_id")) {
            log.warn("Database constraint uk_trips_quote_id violated for quoteId={}", request.quoteId());
            throw new QuoteReplayConflictException(request.quoteId());
        }

        if (message.contains("uk_trips_customer_single_active")) {
            log.warn("Database constraint uk_trips_customer_single_active violated for customerId={}", customerId);
            throw new ActiveTripConflictException(customerId);
        }

        if (message.contains("uk_trips_customer_idempotency")) {
            log.warn("Database constraint uk_trips_customer_idempotency violated for key={}", request.idempotencyKey());
            Optional<Trip> existing = tripRepository.findByCustomerIdAndIdempotencyKey(
                    customerId, request.idempotencyKey());
            if (existing.isPresent()) {
                return validateAndReturnExisting(existing.get(), request);
            }
            throw new IdempotencyConflictException(request.idempotencyKey());
        }

        log.error("Unhandled DataIntegrityViolationException during trip creation: {}", ex.getMessage(), ex);
        throw ex;
    }
}
