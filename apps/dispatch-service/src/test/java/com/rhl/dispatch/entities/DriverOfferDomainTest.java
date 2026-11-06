package com.rhl.dispatch.entities;

import com.rhl.dispatch.exceptions.InvalidOfferStateTransitionException;
import com.rhl.dispatch.exceptions.OfferAccessDeniedException;
import com.rhl.dispatch.exceptions.OfferExpiredException;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DriverOffer Aggregate Root & Domain Invariants Tests")
class DriverOfferDomainTest {

    private final UUID tripId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID vehicleId = UUID.randomUUID();
    private final Instant baseTime = Instant.parse("2026-10-24T10:00:00Z");
    private final Instant expiryTime = Instant.parse("2026-10-24T10:00:15Z");

    private DriverOffer createPendingOffer() {
        return DriverOffer.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .driverId(driverId)
                .vehicleId(vehicleId)
                .serviceType(ServiceType.RIDE)
                .offeredAt(baseTime)
                .expiresAt(expiryTime)
                .build();
    }

    @Test
    @DisplayName("Offer creation initializes with PENDING status and initial audit history")
    void offerCreation_InitializesPendingAndAudit() {
        DriverOffer offer = createPendingOffer();

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(offer.getTripId()).isEqualTo(tripId);
        assertThat(offer.getDriverId()).isEqualTo(driverId);
        assertThat(offer.getVehicleId()).isEqualTo(vehicleId);
        assertThat(offer.getServiceType()).isEqualTo(ServiceType.RIDE);
        assertThat(offer.getOfferedAt()).isEqualTo(baseTime);
        assertThat(offer.getExpiresAt()).isEqualTo(expiryTime);
        assertThat(offer.getVersion()).isEqualTo(0L);

        assertThat(offer.getStatusHistory()).hasSize(1);
        DriverOfferHistory initialHistory = offer.getStatusHistory().get(0);
        assertThat(initialHistory.getFromStatus()).isNull();
        assertThat(initialHistory.getToStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(initialHistory.getActorRole()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("Offer creation rejects when expiresAt is not strictly after offeredAt")
    void offerCreation_FailsWhenExpiryNotAfterOffered() {
        assertThatThrownBy(() ->
                DriverOffer.builder()
                        .id(UUID.randomUUID())
                        .tripId(tripId)
                        .driverId(driverId)
                        .serviceType(ServiceType.RIDE)
                        .offeredAt(baseTime)
                        .expiresAt(baseTime)
                        .build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("must be strictly after OfferedAt");

        assertThatThrownBy(() ->
                DriverOffer.builder()
                        .id(UUID.randomUUID())
                        .tripId(tripId)
                        .driverId(driverId)
                        .serviceType(ServiceType.RIDE)
                        .offeredAt(baseTime)
                        .expiresAt(baseTime.minusSeconds(1))
                        .build()
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Valid accept transitions offer to ACCEPTED and records audit trail")
    void accept_ValidTransition() {
        DriverOffer offer = createPendingOffer();
        Instant acceptTime = baseTime.plusSeconds(5);

        offer.accept(driverId, acceptTime);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(offer.getAcceptedAt()).isEqualTo(acceptTime);
        assertThat(offer.getUpdatedAt()).isEqualTo(acceptTime);

        assertThat(offer.getStatusHistory()).hasSize(2);
        DriverOfferHistory history = offer.getStatusHistory().get(1);
        assertThat(history.getFromStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(history.getToStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(history.getActorId()).isEqualTo(driverId);
        assertThat(history.getActorRole()).isEqualTo("DRIVER");
    }

    @Test
    @DisplayName("Accept fails with OfferAccessDeniedException when called by different driver")
    void accept_FailsForDifferentDriver() {
        DriverOffer offer = createPendingOffer();
        UUID strangerDriverId = UUID.randomUUID();

        assertThatThrownBy(() -> offer.accept(strangerDriverId, baseTime.plusSeconds(5)))
                .isInstanceOf(OfferAccessDeniedException.class)
                .hasMessageContaining(strangerDriverId.toString());
    }

    @Test
    @DisplayName("Accept fails with OfferExpiredException when now is at or after expiresAt")
    void accept_FailsWhenExpired() {
        DriverOffer offer = createPendingOffer();

        // Exactly at expiry time
        assertThatThrownBy(() -> offer.accept(driverId, expiryTime))
                .isInstanceOf(OfferExpiredException.class)
                .hasMessageContaining("expired");

        // After expiry time
        assertThatThrownBy(() -> offer.accept(driverId, expiryTime.plusSeconds(1)))
                .isInstanceOf(OfferExpiredException.class);
    }

    @Test
    @DisplayName("Expiry boundary: 1 millisecond before expiry is valid, at or after is expired")
    void expiryBoundary_Precision() {
        DriverOffer offer = createPendingOffer();

        Instant justBefore = expiryTime.minusMillis(1);
        assertThat(offer.isExpired(justBefore)).isFalse();

        Instant exactExpiry = expiryTime;
        assertThat(offer.isExpired(exactExpiry)).isTrue();

        Instant afterExpiry = expiryTime.plusMillis(1);
        assertThat(offer.isExpired(afterExpiry)).isTrue();
    }

    @Test
    @DisplayName("Valid reject transitions offer to REJECTED and records reason")
    void reject_ValidTransition() {
        DriverOffer offer = createPendingOffer();
        Instant rejectTime = baseTime.plusSeconds(3);

        offer.reject(driverId, "TIRED", rejectTime);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.REJECTED);
        assertThat(offer.getRejectedAt()).isEqualTo(rejectTime);
        assertThat(offer.getRejectionReason()).isEqualTo("TIRED");

        assertThat(offer.getStatusHistory()).hasSize(2);
        DriverOfferHistory history = offer.getStatusHistory().get(1);
        assertThat(history.getFromStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(history.getToStatus()).isEqualTo(OfferStatus.REJECTED);
        assertThat(history.getReason()).isEqualTo("TIRED");
    }

    @Test
    @DisplayName("Reject fails with OfferAccessDeniedException when called by stranger driver")
    void reject_FailsForStrangerDriver() {
        DriverOffer offer = createPendingOffer();
        UUID strangerDriverId = UUID.randomUUID();

        assertThatThrownBy(() -> offer.reject(strangerDriverId, "REASON", baseTime.plusSeconds(2)))
                .isInstanceOf(OfferAccessDeniedException.class);
    }

    @Test
    @DisplayName("Reject fails with OfferExpiredException when now is at or after expiresAt")
    void reject_FailsWhenExpired() {
        DriverOffer offer = createPendingOffer();

        assertThatThrownBy(() -> offer.reject(driverId, "REASON", expiryTime.plusSeconds(1)))
                .isInstanceOf(OfferExpiredException.class);
    }

    @Test
    @DisplayName("Valid expire transitions offer to EXPIRED")
    void expire_ValidTransition() {
        DriverOffer offer = createPendingOffer();
        Instant expireTime = expiryTime.plusSeconds(1);

        offer.expire(expireTime);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(offer.getStatusHistory()).hasSize(2);
        assertThat(offer.getStatusHistory().get(1).getToStatus()).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    @DisplayName("Valid revoke transitions offer to REVOKED with actor role and reason")
    void revoke_ValidTransition() {
        DriverOffer offer = createPendingOffer();
        Instant revokeTime = baseTime.plusSeconds(2);

        offer.revoke("Customer cancelled trip", "SYSTEM", revokeTime);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.REVOKED);
        assertThat(offer.getRevokedAt()).isEqualTo(revokeTime);
        assertThat(offer.getRevocationReason()).isEqualTo("Customer cancelled trip");

        assertThat(offer.getStatusHistory()).hasSize(2);
        DriverOfferHistory history = offer.getStatusHistory().get(1);
        assertThat(history.getToStatus()).isEqualTo(OfferStatus.REVOKED);
        assertThat(history.getActorRole()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("Accept fails when offer is already in terminal state REJECTED")
    void accept_FailsAfterReject() {
        DriverOffer offer = createPendingOffer();
        offer.reject(driverId, "NO_THANKS", baseTime.plusSeconds(2));

        assertThatThrownBy(() -> offer.accept(driverId, baseTime.plusSeconds(4)))
                .isInstanceOf(InvalidOfferStateTransitionException.class)
                .hasMessageContaining("terminal state");
    }

    @Test
    @DisplayName("Reject fails when offer is already in terminal state ACCEPTED")
    void reject_FailsAfterAccept() {
        DriverOffer offer = createPendingOffer();
        offer.accept(driverId, baseTime.plusSeconds(2));

        assertThatThrownBy(() -> offer.reject(driverId, "CHANGE_OF_MIND", baseTime.plusSeconds(4)))
                .isInstanceOf(InvalidOfferStateTransitionException.class)
                .hasMessageContaining("terminal state");
    }
}
