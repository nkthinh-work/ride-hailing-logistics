import { useState, useEffect, useCallback, useRef } from 'react';
import { useAuth } from '../context/AuthContext.tsx';
import { stompClient } from '../services/realtime/stompClient.ts';
import { tripService } from '../services/tripService.ts';
import type {
  TripResponse,
  TripStatusPayload,
  TripStatus,
  EventEnvelope,
} from '../types/index.ts';

const STATUS_PROGRESSION_RANK: Record<TripStatus, number> = {
  CREATED: 1,
  MATCHING: 2,
  ACCEPTED: 3,
  PICKING_UP: 4,
  ARRIVED: 5,
  IN_TRIP: 6,
  COMPLETED: 7,
  CANCELLED: 7,
  NO_DRIVER: 7,
};

export function useTripRealtime(initialTripId?: string | null) {
  const { isAuthenticated } = useAuth();
  const [activeTripId, setActiveTripId] = useState<string | null>(initialTripId || null);
  const [trip, setTrip] = useState<TripResponse | null>(null);
  const [tripStatus, setTripStatus] = useState<TripStatus | null>(null);
  const [assignedDriverId, setAssignedDriverId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const statusRef = useRef<TripStatus | null>(null);
  statusRef.current = tripStatus;

  // Safe status transition preventing backwards regression
  const applySafeStatus = useCallback((newStatus: TripStatus, newDriverId?: string | null) => {
    const current = statusRef.current;
    if (current) {
      // Terminal states cannot be overridden
      if (current === 'COMPLETED' || current === 'CANCELLED' || current === 'NO_DRIVER') {
        return;
      }
      // Regressions to earlier non-terminal states are rejected
      if (STATUS_PROGRESSION_RANK[newStatus] < STATUS_PROGRESSION_RANK[current]) {
        return;
      }
    }

    setTripStatus(newStatus);
    if (newDriverId) {
      setAssignedDriverId(newDriverId);
    }
  }, []);

  // Fetch authoritative state from REST
  const syncAuthoritativeTrip = useCallback(async (tripId: string) => {
    if (!tripId) return;
    setIsLoading(true);
    setErrorMessage(null);

    try {
      const data = await tripService.getTripById(tripId);
      setTrip(data);
      applySafeStatus(data.status, data.driverId);
    } catch (err: unknown) {
      const errObj = err as { message?: string };
      setErrorMessage(errObj?.message || 'Failed to sync trip state.');
    } finally {
      setIsLoading(false);
    }
  }, [applySafeStatus]);

  // Handle incoming STOMP trip event
  const handleTripEvent = useCallback((envelope: EventEnvelope<TripStatusPayload>) => {
    const { eventType, payload } = envelope;
    if (!payload) return;

    // Filter to current trip if tracking a specific trip
    if (activeTripId && payload.tripId !== activeTripId) {
      return;
    }

    // Adopt active tripId if not yet bound
    if (!activeTripId && payload.tripId) {
      setActiveTripId(payload.tripId);
    }

    if (eventType === 'TRIP_DRIVER_ASSIGNED') {
      applySafeStatus('ACCEPTED', payload.driverId);
    } else if (eventType === 'TRIP_STATUS_CHANGED') {
      applySafeStatus(payload.status, payload.driverId);
    } else if (eventType === 'TRIP_CANCELLED') {
      applySafeStatus('CANCELLED', payload.driverId);
    } else if (eventType === 'TRIP_COMPLETED') {
      applySafeStatus('COMPLETED', payload.driverId);
    }
  }, [activeTripId, applySafeStatus]);

  // STOMP subscription & Reconnect listener
  useEffect(() => {
    if (!isAuthenticated) {
      setTrip(null);
      setTripStatus(null);
      setAssignedDriverId(null);
      return;
    }

    // Subscribe to customer trip updates
    const unsubTrips = stompClient.subscribe<TripStatusPayload>(
      '/user/queue/trips',
      handleTripEvent
    );

    // On reconnect: sync authoritative state from REST
    const unsubStatus = stompClient.onStatusChange((status) => {
      if (status === 'CONNECTED' && activeTripId) {
        syncAuthoritativeTrip(activeTripId);
      }
    });

    return () => {
      unsubTrips();
      unsubStatus();
    };
  }, [isAuthenticated, activeTripId, handleTripEvent, syncAuthoritativeTrip]);

  // Initial load when activeTripId changes
  useEffect(() => {
    if (activeTripId) {
      syncAuthoritativeTrip(activeTripId);
    }
  }, [activeTripId, syncAuthoritativeTrip]);

  const cancelActiveTrip = useCallback(async (reason: string) => {
    if (!activeTripId) return;
    setIsLoading(true);
    setErrorMessage(null);

    try {
      const updated = await tripService.cancelTrip(activeTripId, reason);
      setTrip(updated);
      setTripStatus('CANCELLED');
    } catch (err: unknown) {
      const errObj = err as { message?: string };
      setErrorMessage(errObj?.message || 'Failed to cancel trip.');
      throw err;
    } finally {
      setIsLoading(false);
    }
  }, [activeTripId]);

  return {
    activeTripId,
    setActiveTripId,
    trip,
    tripStatus,
    assignedDriverId,
    isLoading,
    errorMessage,
    syncTrip: async () => {
      if (activeTripId) {
        await syncAuthoritativeTrip(activeTripId);
      }
    },
    cancelTrip: cancelActiveTrip,
  };
}
