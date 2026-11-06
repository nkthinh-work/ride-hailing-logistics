package com.rhl.dispatch.fsm;

import com.rhl.dispatch.exceptions.InvalidOfferStateTransitionException;
import com.rhl.dispatch.models.OfferStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OfferStateMachine Finite State Machine Tests")
class OfferStateMachineTest {

    @Test
    @DisplayName("Valid transition: PENDING -> ACCEPTED by DRIVER")
    void validTransition_PendingToAccepted_ByDriver() {
        assertThatCode(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.ACCEPTED, "DRIVER")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Valid transition: PENDING -> ACCEPTED by SYSTEM")
    void validTransition_PendingToAccepted_BySystem() {
        assertThatCode(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.ACCEPTED, "SYSTEM")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Valid transition: PENDING -> REJECTED by DRIVER")
    void validTransition_PendingToRejected_ByDriver() {
        assertThatCode(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.REJECTED, "DRIVER")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Valid transition: PENDING -> EXPIRED by SYSTEM")
    void validTransition_PendingToExpired_BySystem() {
        assertThatCode(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.EXPIRED, "SYSTEM")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Valid transition: PENDING -> REVOKED by SYSTEM, ADMINISTRATOR, SUPPORT_STAFF")
    void validTransition_PendingToRevoked_ByAuthorizedActors() {
        assertThatCode(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.REVOKED, "SYSTEM")
        ).doesNotThrowAnyException();

        assertThatCode(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.REVOKED, "ADMINISTRATOR")
        ).doesNotThrowAnyException();

        assertThatCode(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.REVOKED, "SUPPORT_STAFF")
        ).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = OfferStatus.class, names = {"ACCEPTED", "REJECTED", "EXPIRED", "REVOKED"})
    @DisplayName("Terminal states cannot transition to any other status")
    void terminalStates_CannotTransitionToAnyStatus(OfferStatus terminalStatus) {
        for (OfferStatus target : OfferStatus.values()) {
            assertThatThrownBy(() ->
                    OfferStateMachine.validateTransition(terminalStatus, target, "SYSTEM")
            ).isInstanceOf(InvalidOfferStateTransitionException.class)
             .hasMessageContaining("Cannot transition from terminal state");
        }
    }

    @Test
    @DisplayName("Actor guard: CUSTOMER cannot accept an offer")
    void actorGuard_CustomerCannotAccept() {
        assertThatThrownBy(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.ACCEPTED, "CUSTOMER")
        ).isInstanceOf(InvalidOfferStateTransitionException.class)
         .hasMessageContaining("Only DRIVER or SYSTEM actor can accept an offer");
    }

    @Test
    @DisplayName("Actor guard: CUSTOMER cannot reject an offer")
    void actorGuard_CustomerCannotReject() {
        assertThatThrownBy(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.REJECTED, "CUSTOMER")
        ).isInstanceOf(InvalidOfferStateTransitionException.class)
         .hasMessageContaining("Only DRIVER actor can reject an offer");
    }

    @Test
    @DisplayName("Actor guard: DRIVER cannot expire an offer")
    void actorGuard_DriverCannotExpire() {
        assertThatThrownBy(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.EXPIRED, "DRIVER")
        ).isInstanceOf(InvalidOfferStateTransitionException.class)
         .hasMessageContaining("Only SYSTEM actor can expire an offer");
    }

    @Test
    @DisplayName("Actor guard: DRIVER or CUSTOMER cannot revoke an offer")
    void actorGuard_DriverOrCustomerCannotRevoke() {
        assertThatThrownBy(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.REVOKED, "DRIVER")
        ).isInstanceOf(InvalidOfferStateTransitionException.class)
         .hasMessageContaining("Only SYSTEM, ADMINISTRATOR, or SUPPORT_STAFF can revoke an offer");

        assertThatThrownBy(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.REVOKED, "CUSTOMER")
        ).isInstanceOf(InvalidOfferStateTransitionException.class);
    }

    @Test
    @DisplayName("Validation fails on null arguments")
    void validationFailsOnNullArguments() {
        assertThatThrownBy(() ->
                OfferStateMachine.validateTransition(null, OfferStatus.ACCEPTED, "DRIVER")
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, null, "DRIVER")
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                OfferStateMachine.validateTransition(OfferStatus.PENDING, OfferStatus.ACCEPTED, "")
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
