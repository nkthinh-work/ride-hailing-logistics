package com.rhl.dispatch.services;

import com.rhl.dispatch.configs.DispatchProperties;
import com.rhl.dispatch.dtos.CreateOfferCommand;
import com.rhl.dispatch.dtos.DriverOfferResponse;
import com.rhl.dispatch.entities.DriverOffer;
import com.rhl.dispatch.exceptions.DriverOfferConflictException;
import com.rhl.dispatch.exceptions.OfferAccessDeniedException;
import com.rhl.dispatch.exceptions.OfferExpiredException;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DriverOfferService Domain Unit Tests")
class DriverOfferServiceTest {

    @Mock
    private DriverOfferRepository offerRepository;

    private DispatchProperties properties;
    private Clock fixedClock;
    private DriverOfferService service;

    private final Instant now = Instant.parse("2026-10-24T12:00:00Z");
    private final UUID tripId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID vehicleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new DispatchProperties(15, 3);
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        service = new DriverOfferService(offerRepository, properties, fixedClock);
    }

    private DriverOffer buildOffer(OfferStatus status, Instant offeredAt, Instant expiresAt) {
        DriverOffer offer = DriverOffer.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .driverId(driverId)
                .vehicleId(vehicleId)
                .serviceType(ServiceType.RIDE)
                .offeredAt(offeredAt)
                .expiresAt(expiresAt)
                .build();
        return offer;
    }

    @Test
    @DisplayName("createOffer successfully creates an offer in PENDING status with default TTL 15s")
    void createOffer_Success_WithDefaultTtl() {
        CreateOfferCommand cmd = new CreateOfferCommand(tripId, driverId, vehicleId, ServiceType.RIDE, null);

        when(offerRepository.existsByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), eq(OfferStatus.ACCEPTED), any())).thenReturn(false);
        when(offerRepository.existsByDriverIdAndStatus(driverId, OfferStatus.PENDING)).thenReturn(false);
        when(offerRepository.save(any(DriverOffer.class))).thenAnswer(inv -> inv.getArgument(0));

        DriverOfferResponse response = service.createOffer(cmd);

        assertThat(response.tripId()).isEqualTo(tripId);
        assertThat(response.driverId()).isEqualTo(driverId);
        assertThat(response.status()).isEqualTo(OfferStatus.PENDING);
        assertThat(response.offeredAt()).isEqualTo(now);
        assertThat(response.expiresAt()).isEqualTo(now.plusSeconds(15));

        ArgumentCaptor<DriverOffer> captor = ArgumentCaptor.forClass(DriverOffer.class);
        verify(offerRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OfferStatus.PENDING);
    }

    @Test
    @DisplayName("createOffer rejects when driver already has an active PENDING offer")
    void createOffer_Fails_WhenDriverAlreadyHasPendingOffer() {
        CreateOfferCommand cmd = new CreateOfferCommand(tripId, driverId, vehicleId, ServiceType.RIDE, null);

        when(offerRepository.existsByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), eq(OfferStatus.ACCEPTED), any())).thenReturn(false);
        when(offerRepository.existsByDriverIdAndStatus(driverId, OfferStatus.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> service.createOffer(cmd))
                .isInstanceOf(DriverOfferConflictException.class)
                .hasMessageContaining("already has an active pending offer");

        verify(offerRepository, never()).save(any());
    }

    @Test
    @DisplayName("createOffer rejects when trip already has an ACCEPTED offer")
    void createOffer_Fails_WhenTripAlreadyAccepted() {
        CreateOfferCommand cmd = new CreateOfferCommand(tripId, driverId, vehicleId, ServiceType.RIDE, null);

        when(offerRepository.existsByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), eq(OfferStatus.ACCEPTED), any())).thenReturn(true);

        assertThatThrownBy(() -> service.createOffer(cmd))
                .isInstanceOf(DriverOfferConflictException.class)
                .hasMessageContaining("already has a winning accepted offer");

        verify(offerRepository, never()).save(any());
    }

    @Test
    @DisplayName("acceptOffer successfully accepts valid pending offer")
    void acceptOffer_Success() {
        DriverOffer offer = buildOffer(OfferStatus.PENDING, now.minusSeconds(2), now.plusSeconds(13));
        UUID offerId = offer.getId();

        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
        when(offerRepository.existsByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), eq(OfferStatus.ACCEPTED), any())).thenReturn(false);
        when(offerRepository.save(any(DriverOffer.class))).thenAnswer(inv -> inv.getArgument(0));

        DriverOfferResponse response = service.acceptOffer(offerId, driverId);

        assertThat(response.status()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(response.acceptedAt()).isEqualTo(now);
        verify(offerRepository).save(offer);
    }

    @Test
    @DisplayName("acceptOffer returns idempotent replay when already ACCEPTED by same driver")
    void acceptOffer_IdempotentReplay() {
        DriverOffer offer = buildOffer(OfferStatus.PENDING, now.minusSeconds(10), now.plusSeconds(5));
        offer.accept(driverId, now.minusSeconds(2));
        UUID offerId = offer.getId();

        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));

        DriverOfferResponse response = service.acceptOffer(offerId, driverId);

        assertThat(response.status()).isEqualTo(OfferStatus.ACCEPTED);
        // Does not call save again
        verify(offerRepository, never()).save(any());
    }

    @Test
    @DisplayName("acceptOffer fails with OfferAccessDeniedException for stranger driver")
    void acceptOffer_Fails_ForStrangerDriver() {
        DriverOffer offer = buildOffer(OfferStatus.PENDING, now.minusSeconds(2), now.plusSeconds(13));
        UUID offerId = offer.getId();
        UUID strangerId = UUID.randomUUID();

        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));

        assertThatThrownBy(() -> service.acceptOffer(offerId, strangerId))
                .isInstanceOf(OfferAccessDeniedException.class);
    }

    @Test
    @DisplayName("acceptOffer fails with OfferExpiredException when now >= expiresAt and marks EXPIRED")
    void acceptOffer_Fails_WhenExpired() {
        DriverOffer offer = buildOffer(OfferStatus.PENDING, now.minusSeconds(20), now.minusSeconds(5));
        UUID offerId = offer.getId();

        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
        when(offerRepository.save(any(DriverOffer.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.acceptOffer(offerId, driverId))
                .isInstanceOf(OfferExpiredException.class);

        // Verifies the offer was marked EXPIRED and persisted
        assertThat(offer.getStatus()).isEqualTo(OfferStatus.EXPIRED);
        verify(offerRepository).save(offer);
    }

    @Test
    @DisplayName("acceptOffer fails with DriverOfferConflictException when another driver already won")
    void acceptOffer_Fails_WhenAnotherDriverAlreadyAccepted() {
        DriverOffer offer = buildOffer(OfferStatus.PENDING, now.minusSeconds(2), now.plusSeconds(13));
        UUID offerId = offer.getId();

        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
        when(offerRepository.existsByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), eq(OfferStatus.ACCEPTED), any())).thenReturn(true);

        assertThatThrownBy(() -> service.acceptOffer(offerId, driverId))
                .isInstanceOf(DriverOfferConflictException.class)
                .hasMessageContaining("Another driver has already accepted");
    }

    @Test
    @DisplayName("rejectOffer successfully rejects pending offer")
    void rejectOffer_Success() {
        DriverOffer offer = buildOffer(OfferStatus.PENDING, now.minusSeconds(2), now.plusSeconds(13));
        UUID offerId = offer.getId();

        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
        when(offerRepository.save(any(DriverOffer.class))).thenAnswer(inv -> inv.getArgument(0));

        DriverOfferResponse response = service.rejectOffer(offerId, driverId, "NOT_READY");

        assertThat(response.status()).isEqualTo(OfferStatus.REJECTED);
        assertThat(response.rejectionReason()).isEqualTo("NOT_READY");
        verify(offerRepository).save(offer);
    }

    @Test
    @DisplayName("rejectOffer returns idempotent replay when already REJECTED by same driver")
    void rejectOffer_IdempotentReplay() {
        DriverOffer offer = buildOffer(OfferStatus.PENDING, now.minusSeconds(10), now.plusSeconds(5));
        offer.reject(driverId, "DECLINED", now.minusSeconds(2));
        UUID offerId = offer.getId();

        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));

        DriverOfferResponse response = service.rejectOffer(offerId, driverId, "RETRY");

        assertThat(response.status()).isEqualTo(OfferStatus.REJECTED);
        verify(offerRepository, never()).save(any());
    }

    @Test
    @DisplayName("expireOffer marks offer as EXPIRED")
    void expireOffer_Success() {
        DriverOffer offer = buildOffer(OfferStatus.PENDING, now.minusSeconds(20), now.minusSeconds(5));
        UUID offerId = offer.getId();

        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
        when(offerRepository.save(any(DriverOffer.class))).thenAnswer(inv -> inv.getArgument(0));

        DriverOfferResponse response = service.expireOffer(offerId);

        assertThat(response.status()).isEqualTo(OfferStatus.EXPIRED);
        verify(offerRepository).save(offer);
    }

    @Test
    @DisplayName("revokeAllPendingOffersForTrip revokes only PENDING offers")
    void revokeAllPendingOffersForTrip_Success() {
        DriverOffer pendingOffer = buildOffer(OfferStatus.PENDING, now.minusSeconds(2), now.plusSeconds(13));
        DriverOffer rejectedOffer = buildOffer(OfferStatus.PENDING, now.minusSeconds(10), now.plusSeconds(5));
        rejectedOffer.reject(driverId, "NO", now.minusSeconds(5));

        when(offerRepository.findByTripId(tripId)).thenReturn(List.of(pendingOffer, rejectedOffer));

        service.revokeAllPendingOffersForTrip(tripId, "Trip cancelled", "SYSTEM");

        assertThat(pendingOffer.getStatus()).isEqualTo(OfferStatus.REVOKED);
        assertThat(rejectedOffer.getStatus()).isEqualTo(OfferStatus.REJECTED); // unchanged
        verify(offerRepository, times(1)).save(pendingOffer);
    }
}
