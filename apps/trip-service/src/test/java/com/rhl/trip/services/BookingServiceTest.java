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
import com.rhl.trip.exceptions.PricingServiceUnavailableException;
import com.rhl.trip.exceptions.QuoteAccessDeniedException;
import com.rhl.trip.exceptions.QuoteExpiredException;
import com.rhl.trip.exceptions.QuoteNotFoundException;
import com.rhl.trip.exceptions.QuoteReplayConflictException;
import com.rhl.trip.exceptions.TripAccessDeniedException;
import com.rhl.trip.exceptions.TripNotFoundException;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService Unit Tests")
class BookingServiceTest {

    @Mock
    private TripRepository tripRepository;

    @Mock
    private PricingQuoteClient pricingQuoteClient;

    @Mock
    private AtomicTripCreator atomicTripCreator;

    private BookingService bookingService;

    private final UUID customerId = UUID.randomUUID();
    private final UUID quoteId = UUID.randomUUID();
    private final String idempotencyKey = "idemp-key-12345";

    private CreateTripRequest validRequest;
    private QuoteValidationClientResponse validQuoteSnapshot;
    private Trip mockTrip;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(tripRepository, pricingQuoteClient, atomicTripCreator);

        validRequest = new CreateTripRequest(
                quoteId,
                idempotencyKey,
                ServiceType.RIDE,
                "MOTORBIKE",
                true,
                new BigDecimal("10.7769000"),
                new BigDecimal("106.7009000"),
                new BigDecimal("10.7725000"),
                new BigDecimal("106.6980000")
        );

        validQuoteSnapshot = new QuoteValidationClientResponse(
                quoteId,
                customerId,
                ServiceType.RIDE,
                "MOTORBIKE",
                "DEFAULT",
                new BigDecimal("10.7769000"),
                new BigDecimal("106.7009000"),
                "Saigon Opera House",
                new BigDecimal("10.7725000"),
                new BigDecimal("106.6980000"),
                "Ben Thanh Market",
                new BigDecimal("5.00"),
                10,
                new BigDecimal("42762.50"),
                new BigDecimal("1.25"),
                "VND",
                "DEV-v1.0",
                Instant.now().plusSeconds(300),
                true
        );

        mockTrip = Trip.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .idempotencyKey(idempotencyKey)
                .quoteId(quoteId)
                .serviceType(ServiceType.RIDE)
                .vehicleType("MOTORBIKE")
                .pickupLocation(validQuoteSnapshot.pickupLatitude(), validQuoteSnapshot.pickupLongitude(), validQuoteSnapshot.pickupAddress())
                .dropoffLocation(validQuoteSnapshot.dropoffLatitude(), validQuoteSnapshot.dropoffLongitude(), validQuoteSnapshot.dropoffAddress())
                .estimatedDistanceKm(validQuoteSnapshot.estimatedDistanceKm())
                .estimatedDurationMinutes(validQuoteSnapshot.estimatedDurationMinutes())
                .estimatedFare(validQuoteSnapshot.estimatedFare())
                .surgeMultiplier(validQuoteSnapshot.surgeMultiplier())
                .currency(validQuoteSnapshot.currency())
                .pricingRuleVersion(validQuoteSnapshot.pricingRuleVersion())
                .requestedAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("Successful Booking")
    class SuccessfulBookingTests {

        @Test
        @DisplayName("Valid request creates Trip in CREATED status with snapshot data")
        void bookTrip_Success() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.empty());
            when(tripRepository.existsByQuoteId(quoteId)).thenReturn(false);
            when(tripRepository.existsByCustomerIdAndStatusIn(eq(customerId), any())).thenReturn(false);
            when(pricingQuoteClient.validateQuote(any(QuoteValidationClientRequest.class)))
                    .thenReturn(validQuoteSnapshot);
            when(atomicTripCreator.createTripInTransaction(customerId, validRequest, validQuoteSnapshot))
                    .thenReturn(mockTrip);

            TripResponse response = bookingService.bookTrip(customerId, validRequest);

            assertThat(response).isNotNull();
            assertThat(response.customerId()).isEqualTo(customerId);
            assertThat(response.status()).isEqualTo(TripStatus.CREATED);
            assertThat(response.quoteId()).isEqualTo(quoteId);
            assertThat(response.estimatedFare()).isEqualTo(new BigDecimal("42762.50"));
            assertThat(response.surgeMultiplier()).isEqualTo(new BigDecimal("1.25"));
            assertThat(response.pricingRuleVersion()).isEqualTo("DEV-v1.0");

            verify(atomicTripCreator).createTripInTransaction(customerId, validRequest, validQuoteSnapshot);
        }
    }

    @Nested
    @DisplayName("Idempotency Replay & Conflict")
    class IdempotencyTests {

        @Test
        @DisplayName("Retry with same key and matching semantic payload replays existing trip")
        void retrySameKeyAndPayload_ReplaysExistingTrip() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.of(mockTrip));

            TripResponse response = bookingService.bookTrip(customerId, validRequest);

            assertThat(response.tripId()).isEqualTo(mockTrip.getId());
            assertThat(response.quoteId()).isEqualTo(quoteId);
            verify(pricingQuoteClient, never()).validateQuote(any());
            verify(atomicTripCreator, never()).createTripInTransaction(any(), any(), any());
        }

        @Test
        @DisplayName("Retry with same key but different quoteId throws IdempotencyConflictException")
        void retrySameKeyDifferentQuote_ThrowsIdempotencyConflict() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.of(mockTrip));

            UUID differentQuoteId = UUID.randomUUID();
            CreateTripRequest differentRequest = new CreateTripRequest(
                    differentQuoteId,
                    idempotencyKey,
                    ServiceType.RIDE,
                    "MOTORBIKE",
                    true,
                    null, null, null, null
            );

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, differentRequest))
                    .isInstanceOf(IdempotencyConflictException.class);

            verify(pricingQuoteClient, never()).validateQuote(any());
        }

        @Test
        @DisplayName("Retry with same key but different serviceType throws IdempotencyConflictException")
        void retrySameKeyDifferentServiceType_ThrowsIdempotencyConflict() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.of(mockTrip));

            CreateTripRequest differentRequest = new CreateTripRequest(
                    quoteId,
                    idempotencyKey,
                    ServiceType.DELIVERY,
                    "MOTORBIKE",
                    true,
                    validRequest.pickupLatitude(),
                    validRequest.pickupLongitude(),
                    validRequest.dropoffLatitude(),
                    validRequest.dropoffLongitude()
            );

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, differentRequest))
                    .isInstanceOf(IdempotencyConflictException.class);

            verify(pricingQuoteClient, never()).validateQuote(any());
        }

        @Test
        @DisplayName("Retry with same key but different vehicleType throws IdempotencyConflictException")
        void retrySameKeyDifferentVehicleType_ThrowsIdempotencyConflict() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.of(mockTrip));

            CreateTripRequest differentRequest = new CreateTripRequest(
                    quoteId,
                    idempotencyKey,
                    ServiceType.RIDE,
                    "CAR_4_SEAT",
                    true,
                    validRequest.pickupLatitude(),
                    validRequest.pickupLongitude(),
                    validRequest.dropoffLatitude(),
                    validRequest.dropoffLongitude()
            );

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, differentRequest))
                    .isInstanceOf(IdempotencyConflictException.class);

            verify(pricingQuoteClient, never()).validateQuote(any());
        }

        @Test
        @DisplayName("Retry with same key but unconfirmed surge when trip had surge throws IdempotencyConflictException")
        void retrySameKeyUnconfirmedSurge_ThrowsIdempotencyConflict() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.of(mockTrip));

            CreateTripRequest differentRequest = new CreateTripRequest(
                    quoteId,
                    idempotencyKey,
                    ServiceType.RIDE,
                    "MOTORBIKE",
                    false, // Unconfirmed surge on active surge trip (1.25)
                    validRequest.pickupLatitude(),
                    validRequest.pickupLongitude(),
                    validRequest.dropoffLatitude(),
                    validRequest.dropoffLongitude()
            );

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, differentRequest))
                    .isInstanceOf(IdempotencyConflictException.class);

            verify(pricingQuoteClient, never()).validateQuote(any());
        }

        @Test
        @DisplayName("Retry with same key but divergent coordinates throws IdempotencyConflictException")
        void retrySameKeyDivergentCoordinates_ThrowsIdempotencyConflict() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.of(mockTrip));

            CreateTripRequest differentRequest = new CreateTripRequest(
                    quoteId,
                    idempotencyKey,
                    ServiceType.RIDE,
                    "MOTORBIKE",
                    true,
                    new BigDecimal("10.9999999"), // Different pickup latitude
                    validRequest.pickupLongitude(),
                    validRequest.dropoffLatitude(),
                    validRequest.dropoffLongitude()
            );

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, differentRequest))
                    .isInstanceOf(IdempotencyConflictException.class);

            verify(pricingQuoteClient, never()).validateQuote(any());
        }
    }

    @Nested
    @DisplayName("Quote Replay & Single Active Trip")
    class ReplayAndActiveTripTests {

        @Test
        @DisplayName("Using already booked quoteId throws QuoteReplayConflictException")
        void quoteAlreadyBooked_ThrowsQuoteReplayConflict() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.empty());
            when(tripRepository.existsByQuoteId(quoteId)).thenReturn(true);

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, validRequest))
                    .isInstanceOf(QuoteReplayConflictException.class);

            verify(pricingQuoteClient, never()).validateQuote(any());
        }

        @Test
        @DisplayName("Customer with existing active trip throws ActiveTripConflictException")
        void customerHasActiveTrip_ThrowsActiveTripConflict() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.empty());
            when(tripRepository.existsByQuoteId(quoteId)).thenReturn(false);
            when(tripRepository.existsByCustomerIdAndStatusIn(eq(customerId), any())).thenReturn(true);

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, validRequest))
                    .isInstanceOf(ActiveTripConflictException.class);

            verify(pricingQuoteClient, never()).validateQuote(any());
        }
    }

    @Nested
    @DisplayName("Cross-Service Pricing Quote Validation Failures")
    class PricingValidationFailures {

        @Test
        @DisplayName("Pricing throws QuoteNotFoundException propagates 404")
        void pricingQuoteNotFound_ThrowsException() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.empty());
            when(tripRepository.existsByQuoteId(quoteId)).thenReturn(false);
            when(tripRepository.existsByCustomerIdAndStatusIn(eq(customerId), any())).thenReturn(false);
            when(pricingQuoteClient.validateQuote(any()))
                    .thenThrow(new QuoteNotFoundException(quoteId));

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, validRequest))
                    .isInstanceOf(QuoteNotFoundException.class);
        }

        @Test
        @DisplayName("Pricing throws QuoteAccessDeniedException propagates 403")
        void pricingQuoteAccessDenied_ThrowsException() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.empty());
            when(tripRepository.existsByQuoteId(quoteId)).thenReturn(false);
            when(tripRepository.existsByCustomerIdAndStatusIn(eq(customerId), any())).thenReturn(false);
            when(pricingQuoteClient.validateQuote(any()))
                    .thenThrow(new QuoteAccessDeniedException(quoteId, customerId));

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, validRequest))
                    .isInstanceOf(QuoteAccessDeniedException.class);
        }

        @Test
        @DisplayName("Pricing throws QuoteExpiredException propagates 422")
        void pricingQuoteExpired_ThrowsException() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.empty());
            when(tripRepository.existsByQuoteId(quoteId)).thenReturn(false);
            when(tripRepository.existsByCustomerIdAndStatusIn(eq(customerId), any())).thenReturn(false);
            when(pricingQuoteClient.validateQuote(any()))
                    .thenThrow(new QuoteExpiredException(quoteId));

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, validRequest))
                    .isInstanceOf(QuoteExpiredException.class);
        }

        @Test
        @DisplayName("Pricing service unreachable throws PricingServiceUnavailableException")
        void pricingUnreachable_ThrowsServiceUnavailable() {
            when(tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                    .thenReturn(Optional.empty());
            when(tripRepository.existsByQuoteId(quoteId)).thenReturn(false);
            when(tripRepository.existsByCustomerIdAndStatusIn(eq(customerId), any())).thenReturn(false);
            when(pricingQuoteClient.validateQuote(any()))
                    .thenThrow(new PricingServiceUnavailableException("Connection refused"));

            assertThatThrownBy(() -> bookingService.bookTrip(customerId, validRequest))
                    .isInstanceOf(PricingServiceUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("Query Trip By ID")
    class QueryTripTests {

        @Test
        @DisplayName("Owner can query trip details successfully")
        void getTripById_Owner_Success() {
            when(tripRepository.findById(mockTrip.getId())).thenReturn(Optional.of(mockTrip));

            TripResponse response = bookingService.getTripById(mockTrip.getId(), customerId);

            assertThat(response).isNotNull();
            assertThat(response.tripId()).isEqualTo(mockTrip.getId());
            assertThat(response.customerId()).isEqualTo(customerId);
        }

        @Test
        @DisplayName("Non-owner querying trip throws TripAccessDeniedException")
        void getTripById_NonOwner_ThrowsAccessDenied() {
            when(tripRepository.findById(mockTrip.getId())).thenReturn(Optional.of(mockTrip));
            UUID otherUser = UUID.randomUUID();

            assertThatThrownBy(() -> bookingService.getTripById(mockTrip.getId(), otherUser))
                    .isInstanceOf(TripAccessDeniedException.class);
        }

        @Test
        @DisplayName("Non-existent trip throws TripNotFoundException")
        void getTripById_NotFound_ThrowsNotFound() {
            UUID unknown = UUID.randomUUID();
            when(tripRepository.findById(unknown)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.getTripById(unknown, customerId))
                    .isInstanceOf(TripNotFoundException.class);
        }
    }
}
