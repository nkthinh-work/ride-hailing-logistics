package com.rhl.trip.services;

import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TripProgressionService Unit Tests")
class TripProgressionServiceTest {

    @Mock
    private TripRepository tripRepository;

    private TripProgressionService tripProgressionService;

    private final UUID customerId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID otherDriverId = UUID.randomUUID();
    private final UUID vehicleId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID quoteId = UUID.randomUUID();

    private Trip trip;

    @BeforeEach
    void setUp() {
        tripProgressionService = new TripProgressionService(tripRepository);

        trip = Trip.builder()
                .id(tripId)
                .customerId(customerId)
                .idempotencyKey("idemp-progress-123")
                .quoteId(quoteId)
                .serviceType(ServiceType.RIDE)
                .vehicleType("MOTORBIKE")
                .pickupLocation(new BigDecimal("10.7769"), new BigDecimal("106.7009"), "Pickup Location")
                .dropoffLocation(new BigDecimal("10.7725"), new BigDecimal("106.6980"), "Dropoff Location")
                .estimatedDistanceKm(new BigDecimal("5.00"))
                .estimatedDurationMinutes(10)
                .estimatedFare(new BigDecimal("40000.00"))
                .surgeMultiplier(new BigDecimal("1.00"))
                .currency("VND")
                .pricingRuleVersion("DEV-v1.0")
                .requestedAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("Sequential Driver Progression")
    class SequentialProgressionTests {

        @Test
        @DisplayName("Driver progression from ACCEPTED -> PICKING_UP succeeds")
        void startPickingUp_Success() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, vehicleId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

            TripResponse response = tripProgressionService.startPickingUp(tripId, driverId);

            assertThat(response.status()).isEqualTo(TripStatus.PICKING_UP);
            assertThat(response.driverId()).isEqualTo(driverId);
            verify(tripRepository).save(trip);
        }

        @Test
        @DisplayName("Driver progression from PICKING_UP -> ARRIVED succeeds")
        void confirmArrived_Success() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, vehicleId, "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

            TripResponse response = tripProgressionService.confirmArrived(tripId, driverId);

            assertThat(response.status()).isEqualTo(TripStatus.ARRIVED);
            assertThat(response.arrivedAt()).isNotNull();
            verify(tripRepository).save(trip);
        }

        @Test
        @DisplayName("Driver progression from ARRIVED -> IN_TRIP succeeds")
        void startTrip_Success() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, vehicleId, "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());
            trip.confirmArrived(driverId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

            TripResponse response = tripProgressionService.startTrip(tripId, driverId);

            assertThat(response.status()).isEqualTo(TripStatus.IN_TRIP);
            assertThat(response.startedAt()).isNotNull();
            verify(tripRepository).save(trip);
        }

        @Test
        @DisplayName("Driver progression from IN_TRIP -> COMPLETED succeeds")
        void completeTrip_Success() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, vehicleId, "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());
            trip.confirmArrived(driverId, "DRIVER", Instant.now());
            trip.startTrip(driverId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

            TripResponse response = tripProgressionService.completeTrip(tripId, driverId);

            assertThat(response.status()).isEqualTo(TripStatus.COMPLETED);
            assertThat(response.completedAt()).isNotNull();
            verify(tripRepository).save(trip);
        }
    }

    @Nested
    @DisplayName("Driver Assignment & Authorization Violations")
    class DriverAuthorizationTests {

        @Test
        @DisplayName("Driver not assigned to trip throws TripAccessDeniedException")
        void wrongDriver_ThrowsAccessDenied() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, vehicleId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            assertThatThrownBy(() -> tripProgressionService.startPickingUp(tripId, otherDriverId))
                    .isInstanceOf(TripAccessDeniedException.class);

            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Driver attempting to progress unassigned trip throws TripAccessDeniedException")
        void unassignedTrip_ThrowsAccessDenied() {
            Trip unassigned = Trip.builder()
                    .id(tripId)
                    .customerId(customerId)
                    .idempotencyKey("idemp-unassigned")
                    .quoteId(quoteId)
                    .serviceType(ServiceType.RIDE)
                    .vehicleType("MOTORBIKE")
                    .pickupLocation(new BigDecimal("10.7769"), new BigDecimal("106.7009"), "Pickup")
                    .dropoffLocation(new BigDecimal("10.7725"), new BigDecimal("106.6980"), "Dropoff")
                    .estimatedDistanceKm(new BigDecimal("5.00"))
                    .estimatedDurationMinutes(10)
                    .estimatedFare(new BigDecimal("40000.00"))
                    .surgeMultiplier(new BigDecimal("1.00"))
                    .currency("VND")
                    .pricingRuleVersion("DEV-v1.0")
                    .requestedAt(Instant.now())
                    .build();

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(unassigned));

            assertThatThrownBy(() -> tripProgressionService.startPickingUp(tripId, driverId))
                    .isInstanceOf(TripAccessDeniedException.class)
                    .hasMessageContaining("has not been assigned to any driver");
        }

        @Test
        @DisplayName("Non-existent trip ID throws TripNotFoundException")
        void nonExistentTrip_ThrowsTripNotFound() {
            when(tripRepository.findById(tripId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> tripProgressionService.startPickingUp(tripId, driverId))
                    .isInstanceOf(TripNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Invalid State Transitions")
    class InvalidTransitionTests {

        @Test
        @DisplayName("Skipping intermediate state (e.g. ACCEPTED -> IN_TRIP) throws InvalidTripStateTransitionException")
        void skipState_ThrowsInvalidTransition() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, vehicleId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            // In ACCEPTED status, cannot jump directly to startTrip (IN_TRIP)
            assertThatThrownBy(() -> tripProgressionService.startTrip(tripId, driverId))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("Invalid state transition from ACCEPTED to IN_TRIP");

            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Backward transition (e.g. ARRIVED -> PICKING_UP) throws InvalidTripStateTransitionException")
        void backwardTransition_ThrowsInvalidTransition() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, vehicleId, "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());
            trip.confirmArrived(driverId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            // In ARRIVED status, cannot go back to PICKING_UP
            assertThatThrownBy(() -> tripProgressionService.startPickingUp(tripId, driverId))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("Invalid state transition from ARRIVED to PICKING_UP");

            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Progressing a completed trip throws InvalidTripStateTransitionException")
        void progressCompletedTrip_ThrowsInvalidTransition() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, vehicleId, "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());
            trip.confirmArrived(driverId, "DRIVER", Instant.now());
            trip.startTrip(driverId, "DRIVER", Instant.now());
            trip.complete(driverId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            assertThatThrownBy(() -> tripProgressionService.completeTrip(tripId, driverId))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("terminal state COMPLETED");

            verify(tripRepository, never()).save(any());
        }
    }
}
