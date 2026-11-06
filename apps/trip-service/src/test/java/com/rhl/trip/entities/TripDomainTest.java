package com.rhl.trip.entities;

import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
import com.rhl.trip.exceptions.TripAccessDeniedException;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripDomainTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DRIVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_DRIVER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VEHICLE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID QUOTE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private Trip createStandardTrip() {
        return Trip.builder()
                .idempotencyKey("idem-key-001")
                .customerId(CUSTOMER_ID)
                .quoteId(QUOTE_ID)
                .serviceType(ServiceType.RIDE)
                .vehicleType("MOTORBIKE")
                .pickupLocation(BigDecimal.valueOf(21.028511), BigDecimal.valueOf(105.854444), "Hanoi Opera House")
                .dropoffLocation(BigDecimal.valueOf(21.036868), BigDecimal.valueOf(105.834608), "Ba Dinh Square")
                .estimatedDistanceKm(BigDecimal.valueOf(4.5))
                .estimatedDurationMinutes(15)
                .estimatedFare(BigDecimal.valueOf(35000))
                .surgeMultiplier(BigDecimal.valueOf(1.20))
                .currency("VND")
                .pricingRuleVersion("DEV-v1.0")
                .requestedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Trip creation sets initial status to CREATED and records initial history")
    void tripCreation_setsInitialStatusCreated() {
        Trip trip = createStandardTrip();

        assertThat(trip.getStatus()).isEqualTo(TripStatus.CREATED);
        assertThat(trip.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(trip.getQuoteId()).isEqualTo(QUOTE_ID);
        assertThat(trip.getDriverId()).isNull();
        assertThat(trip.getVehicleId()).isNull();
        assertThat(trip.getSurgeMultiplier()).isEqualByComparingTo("1.20");
        assertThat(trip.getEstimatedFare()).isEqualByComparingTo("35000");
        assertThat(trip.getVersion()).isEqualTo(0L);

        // Verification of initial history entry
        assertThat(trip.getStatusHistory()).hasSize(1);
        TripStatusHistory initialHistory = trip.getStatusHistory().get(0);
        assertThat(initialHistory.getFromStatus()).isNull();
        assertThat(initialHistory.getToStatus()).isEqualTo(TripStatus.CREATED);
        assertThat(initialHistory.getActorId()).isEqualTo(CUSTOMER_ID);
        assertThat(initialHistory.getActorRole()).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("Trip creation fails when coordinates are out of WGS84 bounds")
    void tripCreation_failsOnInvalidCoordinates() {
        assertThatThrownBy(() -> Trip.builder()
                .idempotencyKey("idem-key-002")
                .customerId(CUSTOMER_ID)
                .quoteId(QUOTE_ID)
                .serviceType(ServiceType.RIDE)
                .vehicleType("MOTORBIKE")
                .pickupLocation(BigDecimal.valueOf(95.0), BigDecimal.valueOf(105.85), "Invalid Lat")
                .dropoffLocation(BigDecimal.valueOf(21.0), BigDecimal.valueOf(105.8), "Valid Dropoff")
                .estimatedDistanceKm(BigDecimal.valueOf(5))
                .estimatedDurationMinutes(15)
                .estimatedFare(BigDecimal.valueOf(40000))
                .pricingRuleVersion("DEV-v1.0")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude must be between -90 and 90");

        assertThatThrownBy(() -> Trip.builder()
                .idempotencyKey("idem-key-003")
                .customerId(CUSTOMER_ID)
                .quoteId(QUOTE_ID)
                .serviceType(ServiceType.RIDE)
                .vehicleType("MOTORBIKE")
                .pickupLocation(BigDecimal.valueOf(21.0), BigDecimal.valueOf(195.0), "Invalid Lng")
                .dropoffLocation(BigDecimal.valueOf(21.0), BigDecimal.valueOf(105.8), "Valid Dropoff")
                .estimatedDistanceKm(BigDecimal.valueOf(5))
                .estimatedDurationMinutes(15)
                .estimatedFare(BigDecimal.valueOf(40000))
                .pricingRuleVersion("DEV-v1.0")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitude must be between -180 and 180");
    }

    @Test
    @DisplayName("Full happy path lifecycle: CREATED -> MATCHING -> ACCEPTED -> PICKING_UP -> ARRIVED -> IN_TRIP -> COMPLETED")
    void fullHappyPathLifecycle() {
        Trip trip = createStandardTrip();
        Instant t0 = Instant.now();

        // 1. CREATED -> MATCHING
        trip.startMatching("SYSTEM");
        assertThat(trip.getStatus()).isEqualTo(TripStatus.MATCHING);

        // 2. MATCHING -> ACCEPTED
        Instant t1 = t0.plusSeconds(30);
        trip.accept(DRIVER_ID, VEHICLE_ID, "DRIVER", t1);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACCEPTED);
        assertThat(trip.getDriverId()).isEqualTo(DRIVER_ID);
        assertThat(trip.getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(trip.getAcceptedAt()).isEqualTo(t1);

        // 3. ACCEPTED -> PICKING_UP
        Instant t2 = t1.plusSeconds(10);
        trip.startPickingUp(DRIVER_ID, "DRIVER", t2);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.PICKING_UP);

        // 4. PICKING_UP -> ARRIVED
        Instant t3 = t2.plusSeconds(300);
        trip.confirmArrived(DRIVER_ID, "DRIVER", t3);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ARRIVED);
        assertThat(trip.getArrivedAt()).isEqualTo(t3);

        // 5. ARRIVED -> IN_TRIP
        Instant t4 = t3.plusSeconds(120);
        trip.startTrip(DRIVER_ID, "DRIVER", t4);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.IN_TRIP);
        assertThat(trip.getStartedAt()).isEqualTo(t4);

        // 6. IN_TRIP -> COMPLETED
        Instant t5 = t4.plusSeconds(900);
        trip.complete(DRIVER_ID, "DRIVER", t5);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(trip.getCompletedAt()).isEqualTo(t5);
        assertThat(trip.getStatus().isTerminal()).isTrue();

        // Check history audit trail (initial + 6 transitions = 7 entries)
        assertThat(trip.getStatusHistory()).hasSize(7);
        assertThat(trip.getStatusHistory().get(6).getToStatus()).isEqualTo(TripStatus.COMPLETED);
    }

    @Test
    @DisplayName("Driver invariants: Unassigned driver cannot perform actions on trip")
    void unassignedDriverCannotPerformActions() {
        Trip trip = createStandardTrip();
        trip.startMatching("SYSTEM");
        trip.accept(DRIVER_ID, VEHICLE_ID, "DRIVER", Instant.now());

        // Other driver tries to start picking up
        assertThatThrownBy(() -> trip.startPickingUp(OTHER_DRIVER_ID, "DRIVER", Instant.now()))
                .isInstanceOf(TripAccessDeniedException.class)
                .hasMessageContaining("is not assigned to trip");

        // Other driver tries to confirm arrived
        assertThatThrownBy(() -> trip.confirmArrived(OTHER_DRIVER_ID, "DRIVER", Instant.now()))
                .isInstanceOf(TripAccessDeniedException.class)
                .hasMessageContaining("is not assigned to trip");

        // Other driver tries to start trip
        assertThatThrownBy(() -> trip.startTrip(OTHER_DRIVER_ID, "DRIVER", Instant.now()))
                .isInstanceOf(TripAccessDeniedException.class)
                .hasMessageContaining("is not assigned to trip");

        // Other driver tries to complete trip
        assertThatThrownBy(() -> trip.complete(OTHER_DRIVER_ID, "DRIVER", Instant.now()))
                .isInstanceOf(TripAccessDeniedException.class)
                .hasMessageContaining("is not assigned to trip");
    }

    @Test
    @DisplayName("Cancellation: Customer can cancel in CREATED and MATCHING")
    void customerCancellationInEarlyStates() {
        Trip trip = createStandardTrip();
        Instant cancelTime = Instant.now();

        trip.cancel(CUSTOMER_ID, "CUSTOMER", "Changed my mind", null, cancelTime);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.CANCELLED);
        assertThat(trip.getCancelledAt()).isEqualTo(cancelTime);
        assertThat(trip.getCancelledBy()).isEqualTo(CUSTOMER_ID);
        assertThat(trip.getCancellationReason()).isEqualTo("Changed my mind");
        assertThat(trip.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Cancellation: Customer cannot cancel another customer's trip")
    void otherCustomerCannotCancelTrip() {
        Trip trip = createStandardTrip();
        UUID otherCustomer = UUID.randomUUID();

        assertThatThrownBy(() -> trip.cancel(otherCustomer, "CUSTOMER", "Hacking attempt", null, Instant.now()))
                .isInstanceOf(TripAccessDeniedException.class)
                .hasMessageContaining("does not own trip");
    }

    @Test
    @DisplayName("Cancellation with fee: Driver cancels in PICKING_UP with cancellation fee")
    void driverCancellationWithFee() {
        Trip trip = createStandardTrip();
        trip.startMatching("SYSTEM");
        trip.accept(DRIVER_ID, VEHICLE_ID, "DRIVER", Instant.now());
        trip.startPickingUp(DRIVER_ID, "DRIVER", Instant.now());

        Instant cancelTime = Instant.now();
        BigDecimal fee = BigDecimal.valueOf(10000);
        trip.cancel(DRIVER_ID, "DRIVER", "Flat tire emergency", fee, cancelTime);

        assertThat(trip.getStatus()).isEqualTo(TripStatus.CANCELLED);
        assertThat(trip.getCancelledBy()).isEqualTo(DRIVER_ID);
        assertThat(trip.getCancellationActorRole()).isEqualTo("DRIVER");
        assertThat(trip.getCancellationFee()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Matching timeout: System marks trip as NO_DRIVER")
    void matchingTimeout_marksNoDriver() {
        Trip trip = createStandardTrip();
        trip.startMatching("SYSTEM");

        Instant timeoutTime = Instant.now();
        trip.markNoDriver("SYSTEM", timeoutTime);

        assertThat(trip.getStatus()).isEqualTo(TripStatus.NO_DRIVER);
        assertThat(trip.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Terminal state trip cannot be modified")
    void terminalStateTripCannotBeModified() {
        Trip trip = createStandardTrip();
        trip.cancel(CUSTOMER_ID, "CUSTOMER", "Cancelled", null, Instant.now());

        assertThatThrownBy(() -> trip.startMatching("SYSTEM"))
                .isInstanceOf(InvalidTripStateTransitionException.class)
                .hasMessageContaining("Cannot transition from terminal state");

        assertThatThrownBy(() -> trip.accept(DRIVER_ID, VEHICLE_ID, "DRIVER", Instant.now()))
                .isInstanceOf(InvalidTripStateTransitionException.class)
                .hasMessageContaining("Cannot transition from terminal state");
    }
}
