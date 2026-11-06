import { useState, useEffect, useCallback, useRef } from 'react';
import { stompClient } from '../services/realtime/stompClient.ts';
import { tripService } from '../services/tripService.ts';
import type {
  TripLocationPayload,
  TripLocationSnapshotResponse,
  TripStatus,
  EventEnvelope,
} from '../types/index.ts';

export interface LocationOrderingKey {
  observedAtMs: number;
  sequenceNumber: number;
}

const STREAMABLE_STATUSES: Set<TripStatus> = new Set([
  'ACCEPTED',
  'PICKING_UP',
  'ARRIVED',
  'IN_TRIP',
]);

/**
 * Validates whether incoming GPS packet is strictly newer than current location.
 * Implements Section 9 compound ordering across sessions:
 * - Differentiates old delayed session packets vs new session packets after session reset (seq 105 -> seq 1).
 * - Discards duplicates and out-of-order packets.
 */
export function isNewerLocation(
  incoming: { observedAt: string; sequenceNumber?: number | null },
  current: LocationOrderingKey | null
): boolean {
  if (!current) return true;

  const incomingTimeMs = new Date(incoming.observedAt).getTime();
  const incomingSeq = incoming.sequenceNumber ?? 0;

  // Case 1: Incoming packet timestamp is older than current by more than 1 second -> Drop (delayed older packet)
  if (incomingTimeMs < current.observedAtMs - 1000) {
    return false;
  }

  // Case 2: Incoming packet is distinctly newer by >= 2 seconds -> Accept as new epoch/session generation
  if (incomingTimeMs >= current.observedAtMs + 2000) {
    return true;
  }

  // Case 3: Within same time window (+/- 2s) -> enforce strict monotonic sequenceNumber
  if (incomingSeq > current.sequenceNumber) {
    return true;
  }

  // If sequences match but timestamp is strictly newer by at least 500ms
  if (incomingSeq === current.sequenceNumber && incomingTimeMs > current.observedAtMs + 500) {
    return true;
  }

  // Duplicate or out-of-order
  return false;
}

export function useTripLocation(tripId: string | null, tripStatus: TripStatus | null) {
  const [location, setLocation] = useState<TripLocationPayload | null>(null);
  const [isLoadingSnapshot, setIsLoadingSnapshot] = useState<boolean>(false);
  const [isStale, setIsStale] = useState<boolean>(false);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  const currentOrderingRef = useRef<LocationOrderingKey | null>(null);
  const staleCheckTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const isStreamable = tripId && tripStatus && STREAMABLE_STATUSES.has(tripStatus);

  // Fetch authoritative snapshot from REST
  const fetchSnapshot = useCallback(async (targetTripId: string) => {
    setIsLoadingSnapshot(true);
    try {
      const snap: TripLocationSnapshotResponse = await tripService.getTripLocationSnapshot(targetTripId);
      if (snap.available && snap.latitude != null && snap.longitude != null && snap.recordedAt) {
        const snapTimeMs = new Date(snap.recordedAt).getTime();
        const current = currentOrderingRef.current;

        // Reconcile snapshot vs live: only accept snapshot if live stream has not already superseded it
        if (!current || snapTimeMs >= current.observedAtMs) {
          const newLoc: TripLocationPayload = {
            tripId: snap.tripId,
            driverId: snap.driverId || '',
            latitude: snap.latitude,
            longitude: snap.longitude,
            headingDegrees: null,
            speedMetersPerSecond: null,
            sequenceNumber: current?.sequenceNumber ?? 0,
            observedAt: snap.recordedAt,
          };
          currentOrderingRef.current = {
            observedAtMs: snapTimeMs,
            sequenceNumber: current?.sequenceNumber ?? 0,
          };
          setLocation(newLoc);
          setLastUpdated(new Date(snapTimeMs));
          setIsStale(false);
        }
      }
    } catch {
      // Snapshot unavailable or forbidden
    } finally {
      setIsLoadingSnapshot(false);
    }
  }, []);

  // Handle incoming realtime GPS message
  const handleLocationEvent = useCallback((envelope: EventEnvelope<TripLocationPayload>) => {
    const payload = envelope.payload;
    if (!payload || payload.latitude == null || payload.longitude == null) return;

    if (isNewerLocation(payload, currentOrderingRef.current)) {
      const timeMs = new Date(payload.observedAt).getTime();
      currentOrderingRef.current = {
        observedAtMs: timeMs,
        sequenceNumber: payload.sequenceNumber ?? 0,
      };
      setLocation(payload);
      setLastUpdated(new Date(timeMs));
      setIsStale(false);
    }
  }, []);

  // Subscription and Snapshot loading lifecycle
  useEffect(() => {
    if (!tripId || !isStreamable) {
      if (!isStreamable) {
        // Terminal or unstreamable state: freeze tracking
        setIsStale(false);
      }
      return;
    }

    // Load initial snapshot
    fetchSnapshot(tripId);

    // Subscribe to dynamic trip location topic: /topic/trips.{tripId}.location
    const destination = `/topic/trips.${tripId}.location`;
    const unsubLocation = stompClient.subscribe<TripLocationPayload>(
      destination,
      handleLocationEvent
    );

    // Reconcile snapshot on reconnect
    const unsubStatus = stompClient.onStatusChange((status) => {
      if (status === 'CONNECTED') {
        fetchSnapshot(tripId);
      }
    });

    return () => {
      unsubLocation();
      unsubStatus();
    };
  }, [tripId, isStreamable, fetchSnapshot, handleLocationEvent]);

  // Staleness detection interval
  useEffect(() => {
    if (!isStreamable) {
      if (staleCheckTimerRef.current) {
        clearInterval(staleCheckTimerRef.current);
      }
      return;
    }

    staleCheckTimerRef.current = setInterval(() => {
      if (lastUpdated) {
        const elapsedSeconds = (Date.now() - lastUpdated.getTime()) / 1000;
        if (elapsedSeconds > 15) {
          setIsStale(true);
        }
      }
    }, 5000);

    return () => {
      if (staleCheckTimerRef.current) {
        clearInterval(staleCheckTimerRef.current);
      }
    };
  }, [isStreamable, lastUpdated]);

  return {
    location,
    isStreamable: Boolean(isStreamable),
    isLoadingSnapshot,
    isStale,
    lastUpdated,
    refreshSnapshot: () => tripId && fetchSnapshot(tripId),
  };
}
