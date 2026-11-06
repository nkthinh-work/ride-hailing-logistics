package com.rhl.trip.fsm;

import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
import com.rhl.trip.models.TripStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripStateMachineTest {

    @ParameterizedTest(name = "{0} -> {1} by {2} should be VALID")
    @CsvSource({
            "CREATED, MATCHING, SYSTEM",
            "CREATED, MATCHING, ADMINISTRATOR",
            "CREATED, CANCELLED, CUSTOMER",
            "CREATED, CANCELLED, SUPPORT_STAFF",
            "CREATED, CANCELLED, ADMINISTRATOR",
            "MATCHING, ACCEPTED, DRIVER",
            "MATCHING, NO_DRIVER, SYSTEM",
            "MATCHING, NO_DRIVER, ADMINISTRATOR",
            "MATCHING, CANCELLED, CUSTOMER",
            "MATCHING, CANCELLED, SUPPORT_STAFF",
            "ACCEPTED, PICKING_UP, DRIVER",
            "ACCEPTED, CANCELLED, CUSTOMER",
            "ACCEPTED, CANCELLED, DRIVER",
            "ACCEPTED, CANCELLED, SUPPORT_STAFF",
            "PICKING_UP, ARRIVED, DRIVER",
            "PICKING_UP, CANCELLED, CUSTOMER",
            "PICKING_UP, CANCELLED, DRIVER",
            "PICKING_UP, CANCELLED, SUPPORT_STAFF",
            "ARRIVED, IN_TRIP, DRIVER",
            "ARRIVED, CANCELLED, CUSTOMER",
            "ARRIVED, CANCELLED, DRIVER",
            "ARRIVED, CANCELLED, SUPPORT_STAFF",
            "IN_TRIP, COMPLETED, DRIVER",
            "IN_TRIP, CANCELLED, SUPPORT_STAFF",
            "IN_TRIP, CANCELLED, ADMINISTRATOR"
    })
    @DisplayName("SRS 7.1: Valid state transitions with authorized actors")
    void validStateTransitions(TripStatus from, TripStatus to, String actorRole) {
        assertThat(TripStateMachine.isValidTransition(from, to)).isTrue();
        // Validation method should complete without throwing exception
        TripStateMachine.validateTransition(from, to, actorRole);
    }

    @ParameterizedTest(name = "Terminal state {0} must NOT transition to any state")
    @EnumSource(value = TripStatus.class, names = {"COMPLETED", "CANCELLED", "NO_DRIVER"})
    @DisplayName("SRS 7.1: Terminal states cannot transition to any other state")
    void terminalStatesCannotTransition(TripStatus terminalState) {
        assertThat(terminalState.isTerminal()).isTrue();
        assertThat(terminalState.isActive()).isFalse();
        assertThat(TripStateMachine.getAllowedNextStates(terminalState)).isEmpty();

        for (TripStatus target : TripStatus.values()) {
            assertThat(TripStateMachine.isValidTransition(terminalState, target)).isFalse();
            assertThatThrownBy(() -> TripStateMachine.validateTransition(terminalState, target, "SYSTEM"))
                    .isInstanceOf(InvalidTripStateTransitionException.class)
                    .hasMessageContaining("Cannot transition from terminal state");
        }
    }

    @ParameterizedTest(name = "Invalid skip transition: {0} -> {1}")
    @CsvSource({
            "CREATED, ACCEPTED",
            "CREATED, PICKING_UP",
            "CREATED, ARRIVED",
            "CREATED, IN_TRIP",
            "CREATED, COMPLETED",
            "CREATED, NO_DRIVER",
            "MATCHING, PICKING_UP",
            "MATCHING, ARRIVED",
            "MATCHING, IN_TRIP",
            "MATCHING, COMPLETED",
            "ACCEPTED, ARRIVED",
            "ACCEPTED, IN_TRIP",
            "ACCEPTED, COMPLETED",
            "ACCEPTED, NO_DRIVER",
            "PICKING_UP, IN_TRIP",
            "PICKING_UP, COMPLETED",
            "ARRIVED, COMPLETED"
    })
    @DisplayName("SRS 7.1: Skipping intermediate states is strictly forbidden")
    void skippingStatesIsForbidden(TripStatus from, TripStatus to) {
        assertThat(TripStateMachine.isValidTransition(from, to)).isFalse();
        assertThatThrownBy(() -> TripStateMachine.validateTransition(from, to, "DRIVER"))
                .isInstanceOf(InvalidTripStateTransitionException.class)
                .hasMessageContaining("Invalid state transition from");
    }

    @ParameterizedTest(name = "Invalid backward transition: {0} -> {1}")
    @CsvSource({
            "MATCHING, CREATED",
            "ACCEPTED, CREATED",
            "ACCEPTED, MATCHING",
            "PICKING_UP, ACCEPTED",
            "PICKING_UP, MATCHING",
            "ARRIVED, PICKING_UP",
            "IN_TRIP, ARRIVED",
            "IN_TRIP, PICKING_UP",
            "IN_TRIP, ACCEPTED"
    })
    @DisplayName("SRS 7.1: Backward state transitions are strictly forbidden")
    void backwardTransitionsAreForbidden(TripStatus from, TripStatus to) {
        assertThat(TripStateMachine.isValidTransition(from, to)).isFalse();
        assertThatThrownBy(() -> TripStateMachine.validateTransition(from, to, "DRIVER"))
                .isInstanceOf(InvalidTripStateTransitionException.class);
    }

    @Test
    @DisplayName("SRS 7.1: Customer CANNOT cancel a trip when IN_TRIP (Only Support/Admin exception workflow)")
    void customerCannotCancelInTrip() {
        assertThatThrownBy(() -> TripStateMachine.validateTransition(TripStatus.IN_TRIP, TripStatus.CANCELLED, "CUSTOMER"))
                .isInstanceOf(InvalidTripStateTransitionException.class)
                .hasMessageContaining("exception workflow");
    }

    @Test
    @DisplayName("SRS 7.1: Driver CANNOT cancel a trip when IN_TRIP")
    void driverCannotCancelInTrip() {
        assertThatThrownBy(() -> TripStateMachine.validateTransition(TripStatus.IN_TRIP, TripStatus.CANCELLED, "DRIVER"))
                .isInstanceOf(InvalidTripStateTransitionException.class)
                .hasMessageContaining("exception workflow");
    }

    @Test
    @DisplayName("Driver CANNOT accept trip in CREATED status")
    void driverCannotAcceptCreatedTrip() {
        assertThatThrownBy(() -> TripStateMachine.validateTransition(TripStatus.CREATED, TripStatus.ACCEPTED, "DRIVER"))
                .isInstanceOf(InvalidTripStateTransitionException.class);
    }

    @Test
    @DisplayName("Customer CANNOT complete a trip")
    void customerCannotCompleteTrip() {
        assertThatThrownBy(() -> TripStateMachine.validateTransition(TripStatus.IN_TRIP, TripStatus.COMPLETED, "CUSTOMER"))
                .isInstanceOf(InvalidTripStateTransitionException.class)
                .hasMessageContaining("Only assigned DRIVER can complete trip");
    }

    @Test
    @DisplayName("Null status or blank actor throws InvalidTripStateTransitionException")
    void nullStatusOrBlankActorThrows() {
        assertThatThrownBy(() -> TripStateMachine.validateTransition(null, TripStatus.MATCHING, "SYSTEM"))
                .isInstanceOf(InvalidTripStateTransitionException.class);

        assertThatThrownBy(() -> TripStateMachine.validateTransition(TripStatus.CREATED, null, "SYSTEM"))
                .isInstanceOf(InvalidTripStateTransitionException.class);

        assertThatThrownBy(() -> TripStateMachine.validateTransition(TripStatus.CREATED, TripStatus.MATCHING, null))
                .isInstanceOf(InvalidTripStateTransitionException.class);

        assertThatThrownBy(() -> TripStateMachine.validateTransition(TripStatus.CREATED, TripStatus.MATCHING, "   "))
                .isInstanceOf(InvalidTripStateTransitionException.class);
    }
}
