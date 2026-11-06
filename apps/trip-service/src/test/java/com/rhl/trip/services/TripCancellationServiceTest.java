package com.rhl.trip.services;

import com.rhl.trip.dtos.CancelTripRequest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TripCancellationService Unit Tests")
class TripCancellationServiceTest {

    @Mock
    private TripRepository tripRepository;

    @Mock
    private CancellationPolicyService cancellationPolicyService;

    private TripCancellationService tripCancellationService;

    private final UUID customerId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID otherDriverId = UUID.randomUUID();
    private final UUID otherCustomerId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID quoteId = UUID.randomUUID();

    private Trip trip;

    @BeforeEach
    void setUp() {
        tripCancellationService = new TripCancellationService(tripRepository, cancellationPolicyService);

        trip = Trip.builder()
                .id(tripId)
                .customerId(customerId)
                .idempotencyKey("idemp-test-123")
                .quoteId(quoteId)
                .serviceType(ServiceType.RIDE)
                .vehicleType("MOTORBIKE")
                .pickupLocation(new BigDecimal("10.7769"), new BigDecimal("106.7009"), "Pickup Address")
                .dropoffLocation(new BigDecimal("10.7725"), new BigDecimal("106.6980"), "Dropoff Address")
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
    @DisplayName("Customer Cancellation")
    class CustomerCancellationTests {

        @Test
        @DisplayName("Customer cancels own trip in CREATED status - success with 0 fee")
        void customerCancel_Created_Success() {
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(cancellationPolicyService.calculateCancellationFee(eq(trip), eq(customerId), eq("CUSTOMER"), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CancelTripRequest request = new CancelTripRequest("CUSTOMER_CHANGE_OF_MIND", "Changed my plan");
            TripResponse response = tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", request);

            assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);
            assertThat(response.cancelledBy()).isEqualTo(customerId);
            assertThat(response.cancellationActorRole()).isEqualTo("CUSTOMER");
            assertThat(response.cancellationReason()).isEqualTo("CUSTOMER_CHANGE_OF_MIND: Changed my plan");
            assertThat(response.cancellationFee()).isEqualTo(BigDecimal.ZERO);
            verify(tripRepository).save(trip);
        }

        @Test
        @DisplayName("Customer cancels own trip in MATCHING status - success")
        void customerCancel_Matching_Success() {
            trip.startMatching("SYSTEM");
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(cancellationPolicyService.calculateCancellationFee(any(), any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CancelTripRequest request = new CancelTripRequest("WAITING_TOO_LONG");
            TripResponse response = tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", request);

            assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);
            assertThat(response.cancellationReason()).isEqualTo("WAITING_TOO_LONG");
        }

        @Test
        @DisplayName("Customer cancels own trip in ACCEPTED status - success")
        void customerCancel_Accepted_Success() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, UUID.randomUUID(), "DRIVER", Instant.now());
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(cancellationPolicyService.calculateCancellationFee(any(), any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CancelTripRequest request = new CancelTripRequest("FOUND_ALTERNATIVE");
            TripResponse response = tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", request);

            assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);
        }

        @Test
        @DisplayName("Customer cancels own trip in ARRIVED status - success")
        void customerCancel_Arrived_Success() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, UUID.randomUUID(), "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());
            trip.confirmArrived(driverId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(cancellationPolicyService.calculateCancellationFee(any(), any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CancelTripRequest request = new CancelTripRequest("CANNOT_PROCEED");
            TripResponse response = tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", request);

            assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);
        }

        @Test
        @DisplayName("Customer attempting to cancel another customer's trip throws TripAccessDeniedException")
        void customerCancel_WrongCustomer_ThrowsAccessDenied() {
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            CancelTripRequest request = new CancelTripRequest("CUSTOMER_CHANGE_OF_MIND");
            assertThatThrownBy(() -> tripCancellationService.cancelTrip(tripId, otherCustomerId, "CUSTOMER", request))
                    .isInstanceOf(TripAccessDeniedException.class);

            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Customer attempting to cancel trip in IN_TRIP status throws InvalidTripStateTransitionException")
        void customerCancel_InTrip_ThrowsInvalidTransition() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, UUID.randomUUID(), "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());
            trip.confirmArrived(driverId, "DRIVER", Instant.now());
            trip.startTrip(driverId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            CancelTripRequest request = new CancelTripRequest("CUSTOMER_CHANGE_OF_MIND");
            assertThatThrownBy(() -> tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", request))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("IN_TRIP status can only be cancelled via approved exception workflow");

            verify(tripRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Driver Cancellation")
    class DriverCancellationTests {

        @Test
        @DisplayName("Assigned driver cancels trip in ACCEPTED status - success")
        void driverCancel_Accepted_Success() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, UUID.randomUUID(), "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(cancellationPolicyService.calculateCancellationFee(any(), any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CancelTripRequest request = new CancelTripRequest("VEHICLE_BREAKDOWN", "Flat tire on way to pickup");
            TripResponse response = tripCancellationService.cancelTrip(tripId, driverId, "DRIVER", request);

            assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);
            assertThat(response.cancelledBy()).isEqualTo(driverId);
            assertThat(response.cancellationActorRole()).isEqualTo("DRIVER");
        }

        @Test
        @DisplayName("Assigned driver cancels trip in ARRIVED status (Passenger No-Show) - success")
        void driverCancel_ArrivedNoShow_Success() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, UUID.randomUUID(), "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());
            trip.confirmArrived(driverId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(cancellationPolicyService.calculateCancellationFee(any(), any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CancelTripRequest request = new CancelTripRequest("PASSENGER_NO_SHOW", "Waited 10 mins with no contact");
            TripResponse response = tripCancellationService.cancelTrip(tripId, driverId, "DRIVER", request);

            assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);
            assertThat(response.cancellationReason()).contains("PASSENGER_NO_SHOW");
        }

        @Test
        @DisplayName("Unassigned driver attempting to cancel trip throws TripAccessDeniedException")
        void driverCancel_WrongDriver_ThrowsAccessDenied() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, UUID.randomUUID(), "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            CancelTripRequest request = new CancelTripRequest("DRIVER_EMERGENCY");
            assertThatThrownBy(() -> tripCancellationService.cancelTrip(tripId, otherDriverId, "DRIVER", request))
                    .isInstanceOf(TripAccessDeniedException.class);

            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Driver attempting to cancel in CREATED status (unassigned) throws TripAccessDeniedException")
        void driverCancel_UnassignedTrip_ThrowsAccessDenied() {
            // trip is in CREATED status with driverId null
            Trip unassignedTrip = Trip.builder()
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

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(unassignedTrip));

            CancelTripRequest request = new CancelTripRequest("DRIVER_EMERGENCY");
            assertThatThrownBy(() -> tripCancellationService.cancelTrip(tripId, driverId, "DRIVER", request))
                    .isInstanceOf(TripAccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Administrative & Exceptional Cancellation")
    class AdminCancellationTests {

        @Test
        @DisplayName("Support staff can cancel trip in IN_TRIP status via approved exception workflow")
        void supportStaffCancel_InTrip_Success() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, UUID.randomUUID(), "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());
            trip.confirmArrived(driverId, "DRIVER", Instant.now());
            trip.startTrip(driverId, "DRIVER", Instant.now());

            UUID staffId = UUID.randomUUID();
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(cancellationPolicyService.calculateCancellationFee(any(), any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CancelTripRequest request = new CancelTripRequest("OPERATIONAL_INCIDENT", "Vehicle involved in accident");
            TripResponse response = tripCancellationService.cancelTrip(tripId, staffId, "SUPPORT_STAFF", request);

            assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);
            assertThat(response.cancelledBy()).isEqualTo(staffId);
            assertThat(response.cancellationActorRole()).isEqualTo("SUPPORT_STAFF");
        }

        @Test
        @DisplayName("Administrator can cancel trip in any active status")
        void adminCancel_AnyActiveState_Success() {
            UUID adminId = UUID.randomUUID();
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
            when(cancellationPolicyService.calculateCancellationFee(any(), any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CancelTripRequest request = new CancelTripRequest("ADMIN_OVERRIDE", "Manual customer support override");
            TripResponse response = tripCancellationService.cancelTrip(tripId, adminId, "ADMINISTRATOR", request);

            assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);
            assertThat(response.cancelledBy()).isEqualTo(adminId);
            assertThat(response.cancellationActorRole()).isEqualTo("ADMINISTRATOR");
        }
    }

    @Nested
    @DisplayName("Idempotency & Terminal States")
    class IdempotencyAndTerminalTests {

        @Test
        @DisplayName("Repeated cancellation by same actor is idempotent - returns existing cancelled trip")
        void repeatedCancel_SameActor_IsIdempotent() {
            trip.cancel(customerId, "CUSTOMER", "INITIAL_REASON", BigDecimal.ZERO, Instant.now());
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            CancelTripRequest request = new CancelTripRequest("REPEATED_CANCEL");
            TripResponse response = tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", request);

            assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);
            assertThat(response.cancellationReason()).isEqualTo("INITIAL_REASON");
            verify(tripRepository, never()).save(any());
        }

        @Test
        @DisplayName("Attempting to cancel already CANCELLED trip by another actor throws InvalidTripStateTransitionException")
        void repeatedCancel_DifferentActor_ThrowsInvalidTransition() {
            trip.cancel(customerId, "CUSTOMER", "INITIAL_REASON", BigDecimal.ZERO, Instant.now());
            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            CancelTripRequest request = new CancelTripRequest("DRIVER_CANCEL");
            assertThatThrownBy(() -> tripCancellationService.cancelTrip(tripId, driverId, "DRIVER", request))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("already cancelled by CUSTOMER");
        }

        @Test
        @DisplayName("Attempting to cancel COMPLETED trip throws InvalidTripStateTransitionException")
        void cancelCompletedTrip_ThrowsInvalidTransition() {
            trip.startMatching("SYSTEM");
            trip.accept(driverId, UUID.randomUUID(), "DRIVER", Instant.now());
            trip.startPickingUp(driverId, "DRIVER", Instant.now());
            trip.confirmArrived(driverId, "DRIVER", Instant.now());
            trip.startTrip(driverId, "DRIVER", Instant.now());
            trip.complete(driverId, "DRIVER", Instant.now());

            when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

            CancelTripRequest request = new CancelTripRequest("CANCEL_COMPLETED");
            assertThatThrownBy(() -> tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", request))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("terminal state COMPLETED");
        }

        @Test
        @DisplayName("Non-existent trip ID throws TripNotFoundException")
        void cancelNonExistentTrip_ThrowsTripNotFound() {
            when(tripRepository.findById(tripId)).thenReturn(Optional.empty());

            CancelTripRequest request = new CancelTripRequest("CANCEL");
            assertThatThrownBy(() -> tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", request))
                    .isInstanceOf(TripNotFoundException.class);
        }
    }
}
