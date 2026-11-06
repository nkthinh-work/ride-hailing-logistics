import { useState, useEffect, useCallback, useRef } from 'react';
import { useAuth } from '../context/AuthContext.tsx';
import { stompClient } from '../services/realtime/stompClient.ts';
import { offerService } from '../services/offerService.ts';
import type {
  DriverOfferPayload,
  EventEnvelope,
  AssignmentStatus,
  ApiError,
} from '../types/index.ts';

export function useDriverOffers() {
  const { hasRole, isAuthenticated } = useAuth();
  const isDriver = isAuthenticated && hasRole('DRIVER');

  const [activeOffer, setActiveOffer] = useState<DriverOfferPayload | null>(null);
  const [assignmentStatus, setAssignmentStatus] = useState<AssignmentStatus | null>(null);
  const [secondsRemaining, setSecondsRemaining] = useState<number>(0);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const countdownIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Synchronize authoritative pending offer from REST
  const syncAuthoritativeOffer = useCallback(async () => {
    if (!isDriver) return;
    try {
      const pending = await offerService.getPendingOffer();
      if (pending && pending.status === 'PENDING') {
        const expiresAtMillis = new Date(pending.expiresAt).getTime();
        const now = Date.now();
        if (expiresAtMillis > now) {
          setActiveOffer((prev) => {
            // Preserve existing coordinates / addresses if we already had them from STOMP
            return {
              offerId: pending.id,
              tripId: pending.tripId,
              driverId: pending.driverId,
              serviceType: pending.serviceType,
              pickupLatitude: prev?.pickupLatitude ?? 0,
              pickupLongitude: prev?.pickupLongitude ?? 0,
              pickupAddress: prev?.pickupAddress ?? 'Pickup Point',
              dropoffAddress: prev?.dropoffAddress ?? 'Destination',
              estimatedDistanceKm: prev?.estimatedDistanceKm ?? null,
              estimatedFare: prev?.estimatedFare ?? null,
              offeredAt: pending.offeredAt,
              expiresAt: pending.expiresAt,
              status: pending.status,
            };
          });
          setAssignmentStatus('OFFER_PENDING');
          setErrorMessage(null);
          return;
        }
      }
      // No valid active pending offer
      setActiveOffer(null);
      setAssignmentStatus(null);
    } catch {
      // Ignore initial load or network errors during reconciliation
    }
  }, [isDriver]);

  // Handle incoming realtime offer event
  const handleOfferEvent = useCallback((envelope: EventEnvelope<DriverOfferPayload>) => {
    const { eventType, payload } = envelope;

    if (eventType === 'DRIVER_OFFER_CREATED') {
      setActiveOffer(payload);
      setAssignmentStatus('OFFER_PENDING');
      setErrorMessage(null);
    } else if (eventType === 'DRIVER_OFFER_EXPIRED') {
      if (activeOffer && activeOffer.offerId === payload.offerId) {
        setAssignmentStatus('EXPIRED');
      }
    } else if (eventType === 'DRIVER_OFFER_REVOKED') {
      if (activeOffer && activeOffer.offerId === payload.offerId) {
        setAssignmentStatus('REVOKED');
      }
    } else if (eventType === 'DRIVER_OFFER_ASSIGNMENT_CONFIRMED') {
      if (activeOffer && activeOffer.offerId === payload.offerId) {
        setAssignmentStatus('ASSIGNMENT_CONFIRMED');
      }
    }
  }, [activeOffer]);

  // Subscribe to driver queues & setup reconnect listener
  useEffect(() => {
    if (!isDriver) {
      setActiveOffer(null);
      setAssignmentStatus(null);
      return;
    }

    // Initial sync
    syncAuthoritativeOffer();

    // Subscribe to driver offer channels
    const unsubOffers = stompClient.subscribe<DriverOfferPayload>(
      '/user/queue/offers',
      handleOfferEvent
    );
    const unsubUpdates = stompClient.subscribe<DriverOfferPayload>(
      '/user/queue/offer-updates',
      handleOfferEvent
    );

    // Reconcile on reconnect
    const unsubStatus = stompClient.onStatusChange((status) => {
      if (status === 'CONNECTED') {
        syncAuthoritativeOffer();
      }
    });

    return () => {
      unsubOffers();
      unsubUpdates();
      unsubStatus();
    };
  }, [isDriver, handleOfferEvent, syncAuthoritativeOffer]);

  // Countdown timer effect
  useEffect(() => {
    if (countdownIntervalRef.current) {
      clearInterval(countdownIntervalRef.current);
      countdownIntervalRef.current = null;
    }

    if (!activeOffer || !activeOffer.expiresAt || assignmentStatus === 'ASSIGNMENT_CONFIRMED') {
      setSecondsRemaining(0);
      return;
    }

    const updateCountdown = () => {
      const expiresAt = new Date(activeOffer.expiresAt).getTime();
      const diffSeconds = Math.max(0, Math.floor((expiresAt - Date.now()) / 1000));
      setSecondsRemaining(diffSeconds);

      if (diffSeconds === 0 && assignmentStatus === 'OFFER_PENDING') {
        setAssignmentStatus('EXPIRED');
      }
    };

    updateCountdown();
    countdownIntervalRef.current = setInterval(updateCountdown, 1000);

    return () => {
      if (countdownIntervalRef.current) {
        clearInterval(countdownIntervalRef.current);
      }
    };
  }, [activeOffer, assignmentStatus]);

  // Accept offer via REST
  const acceptOffer = useCallback(async (offerId: string) => {
    if (isSubmitting) return;
    setIsSubmitting(true);
    setErrorMessage(null);
    setAssignmentStatus('ACCEPT_REQUEST_SENT');

    try {
      const res = await offerService.acceptOffer(offerId);
      // REST succeeded: offer is ACCEPTED, now awaiting assignment confirmation
      setAssignmentStatus('OFFER_ACCEPTED');
      if (res.status === 'ACCEPTED') {
        // Offer marked accepted on server
      }
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      if (apiErr?.status === 409) {
        setAssignmentStatus('EXPIRED');
        setErrorMessage('This offer has expired or has already been assigned to another driver.');
      } else {
        setAssignmentStatus('ASSIGNMENT_FAILED');
        setErrorMessage(apiErr?.message || 'Failed to accept offer. Please check connection.');
      }
    } finally {
      setIsSubmitting(false);
    }
  }, [isSubmitting]);

  // Reject offer via REST
  const rejectOffer = useCallback(async (offerId: string, reason?: string) => {
    if (isSubmitting) return;
    setIsSubmitting(true);
    setErrorMessage(null);

    try {
      await offerService.rejectOffer(offerId, reason);
      setAssignmentStatus('REJECTED');
      setTimeout(() => {
        setActiveOffer(null);
        setAssignmentStatus(null);
      }, 1500);
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      setErrorMessage(apiErr?.message || 'Failed to reject offer.');
    } finally {
      setIsSubmitting(false);
    }
  }, [isSubmitting]);

  const clearOffer = useCallback(() => {
    setActiveOffer(null);
    setAssignmentStatus(null);
    setErrorMessage(null);
  }, []);

  return {
    isDriver,
    activeOffer,
    assignmentStatus,
    secondsRemaining,
    isSubmitting,
    errorMessage,
    acceptOffer,
    rejectOffer,
    clearOffer,
    refreshOffers: syncAuthoritativeOffer,
  };
}
