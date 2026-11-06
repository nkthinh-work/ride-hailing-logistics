package com.rhl.trip.services;

import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
import com.rhl.trip.exceptions.TripNotFoundException;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DispatchTripAssignmentService Unit Tests")
class DispatchTripAssignmentServiceTest {

    @Mock
    private TripRepository tripRepository;

    @InjectMocks
    private DispatchTripAssignmentService assignmentService;

    private UUID tripId;
    private UUID customerId;
    private UUID driverId;
    private UUID vehicleId;
    private UUID quoteId;
    private Trip trip;

    @BeforeEach
    void setUp() {
        tripId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        driverId = UUID.randomUUID();
        vehicleId = UUID.randomUUID();
        quoteId = UUID.randomUUID();

        trip = Trip.builder()
                .id(tripId)
                .customerId(customerId)
                .idempotencyKey("idemp-assign-123")
                .quoteId(quoteId)
                .serviceType(ServiceType.RIDE)
                .vehicleType("MOTORBIKE")
                .pickupLocation(new BigDecimal("21.0285000"), new BigDecimal("105.8542000"), "Hanoi")
                .dropoffLocation(new BigDecimal("21.0300000"), new BigDecimal("105.8500000"), "Hoan Kiem")
                .estimatedDistanceKm(new BigDecimal("2.50"))
                .estimatedDurationMinutes(10)
                .estimatedFare(new BigDecimal("30000.00"))
                .surgeMultiplier(BigDecimal.valueOf(1.00))
                .currency("VND")
                .pricingRuleVersion("DEV-v1.0")
                .requestedAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("assignDriver tests")
    class AssignDriverTests {

        @Test
        @DisplayName("Successfully assign driver to MATCHING trip -> ACCEPTED")
        void assignDriver_matchingTrip_success() {
            trip.startMatching("SYSTEM");
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(tripRepository.existsByDriverIdAndStatusIn(eq(driverId), any())).thenReturn(false);
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            TripResponse response = assignmentService.assignDriver(tripId, driverId, vehicleId, "DRIVER");

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TripStatus.ACCEPTED);
            assertThat(response.driverId()).isEqualTo(driverId);
            verify(tripRepository).save(trip);
        }

        @Test
        @DisplayName("Idempotent replay when already ACCEPTED with same driver and vehicle")
        void assignDriver_alreadyAcceptedSameDriver_idempotentSuccess() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, vehicleId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            TripResponse response = assignmentService.assignDriver(tripId, driverId, vehicleId, "DRIVER");

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TripStatus.ACCEPTED);
            assertThat(response.driverId()).isEqualTo(driverId);
            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Conflict when already ACCEPTED with a different driver")
        void assignDriver_alreadyAcceptedDifferentDriver_throwsConflict() {
            UUID otherDriver = UUID.randomUUID();
            trip.startMatching("SYSTEM");
            trip.accept(otherDriver, vehicleId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            assertThatThrownBy(() -> assignmentService.assignDriver(tripId, driverId, vehicleId, "DRIVER"))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("already assigned to a different driver");
            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Driver already has active trip -> throws InvalidTripStateTransitionException")
        void assignDriver_driverAlreadyBusy_throwsException() {
            trip.startMatching("SYSTEM");
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(tripRepository.existsByDriverIdAndStatusIn(eq(driverId), any())).thenReturn(true);

            assertThatThrownBy(() -> assignmentService.assignDriver(tripId, driverId, vehicleId, "DRIVER"))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("already has an active trip in progress");
            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Cannot assign if trip is not in MATCHING status (e.g. CANCELLED)")
        void assignDriver_tripCancelled_throwsException() {
            trip.cancel(customerId, "CUSTOMER", "Customer cancelled", BigDecimal.ZERO, Instant.now());
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            assertThatThrownBy(() -> assignmentService.assignDriver(tripId, driverId, vehicleId, "DRIVER"))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("Cannot assign driver to trip");
            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws TripNotFoundException if trip does not exist")
        void assignDriver_tripNotFound_throwsException() {
            when(tripRepository.findById(tripId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> assignmentService.assignDriver(tripId, driverId, vehicleId, "DRIVER"))
                    .isInstanceOf(TripNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("startMatching tests")
    class StartMatchingTests {

        @Test
        @DisplayName("Successfully start matching from CREATED -> MATCHING")
        void startMatching_createdTrip_success() {
            // trip is in CREATED
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            TripResponse response = assignmentService.startMatching(tripId);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TripStatus.MATCHING);
            verify(tripRepository).save(trip);
        }

        @Test
        @DisplayName("Idempotent replay if already MATCHING")
        void startMatching_alreadyMatching_returnsCurrent() {
            trip.startMatching("SYSTEM");
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            TripResponse response = assignmentService.startMatching(tripId);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TripStatus.MATCHING);
            verify(tripRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markNoDriver tests")
    class MarkNoDriverTests {

        @Test
        @DisplayName("Successfully mark MATCHING trip as NO_DRIVER")
        void markNoDriver_matchingTrip_success() {
            trip.startMatching("SYSTEM");
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            TripResponse response = assignmentService.markNoDriver(tripId, "Candidates exhausted");

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TripStatus.NO_DRIVER);
            verify(tripRepository).save(trip);
        }

        @Test
        @DisplayName("Idempotent replay if already NO_DRIVER")
        void markNoDriver_alreadyNoDriver_returnsCurrent() {
            trip.startMatching("SYSTEM");
            trip.markNoDriver("SYSTEM", Instant.now());
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            TripResponse response = assignmentService.markNoDriver(tripId, "Reason");

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TripStatus.NO_DRIVER);
            verify(tripRepository, never()).save(any());
        }
    }
}
