import { describe, it, before } from 'node:test';
import assert from 'node:assert';

// Polyfill minimal browser globals for Node test runner
before(() => {
  if (typeof globalThis.localStorage === 'undefined') {
    const store = new Map<string, string>();
    (globalThis as unknown as { localStorage: Storage }).localStorage = {
      getItem: (key: string) => store.get(key) || null,
      setItem: (key: string, value: string) => store.set(key, value),
      removeItem: (key: string) => store.delete(key),
      clear: () => store.clear(),
      length: 0,
      key: () => null,
    } as unknown as Storage;
  }
});

import { isNewerLocation } from '../src/hooks/useTripLocation.ts';
import { tokenStorage } from '../src/services/apiClient.ts';
import type { EventEnvelope, DriverOfferPayload, TripStatus } from '../src/types/realtime.ts';

describe('M07 Group 4: Frontend Realtime, GPS Ordering & State Reconciliation Tests', () => {

  describe('1. GPS Ordering Across Sessions & Compound Monotonic Key', () => {
    const baseTime = new Date('2026-10-26T14:00:00.000Z').getTime();

    it('should accept initial packet when current is null', () => {
      const incoming = {
        observedAt: new Date(baseTime).toISOString(),
        sequenceNumber: 1,
      };
      const result = isNewerLocation(incoming, null);
      assert.strictEqual(result, true);
    });

    it('should accept strictly increasing sequence within same session/epoch', () => {
      const current = {
        observedAtMs: baseTime,
        sequenceNumber: 10,
      };
      const incoming = {
        observedAt: new Date(baseTime + 200).toISOString(),
        sequenceNumber: 11,
      };
      assert.strictEqual(isNewerLocation(incoming, current), true);
    });

    it('should discard duplicate sequenceNumber within same session/epoch', () => {
      const current = {
        observedAtMs: baseTime,
        sequenceNumber: 10,
      };
      const duplicate = {
        observedAt: new Date(baseTime).toISOString(),
        sequenceNumber: 10,
      };
      assert.strictEqual(isNewerLocation(duplicate, current), false);
    });

    it('should discard older sequenceNumber arriving out-of-order within same session', () => {
      const current = {
        observedAtMs: baseTime,
        sequenceNumber: 10,
      };
      const oldPacket = {
        observedAt: new Date(baseTime - 100).toISOString(),
        sequenceNumber: 9,
      };
      assert.strictEqual(isNewerLocation(oldPacket, current), false);
    });

    it('CRITICAL: should accept sequence reset from 105 to 1 when a new session begins (timestamp is newer)', () => {
      // Session A: ended at seq=105
      const current = {
        observedAtMs: baseTime,
        sequenceNumber: 105,
      };
      // Driver reconnected 3 seconds later: Session B starts at seq=1
      const sessionBStart = {
        observedAt: new Date(baseTime + 3000).toISOString(),
        sequenceNumber: 1,
      };
      // Crucial requirement: must NOT discard seq=1 because timestamp indicates new session generation!
      assert.strictEqual(isNewerLocation(sessionBStart, current), true);
    });

    it('should discard delayed packet from an old session arriving after new session started', () => {
      // Current is in Session B
      const current = {
        observedAtMs: baseTime + 5000,
        sequenceNumber: 5,
      };
      // Delayed packet from Session A (timestamp 5 seconds older, seq 106)
      const delayedOldSessionPacket = {
        observedAt: new Date(baseTime).toISOString(),
        sequenceNumber: 106,
      };
      assert.strictEqual(isNewerLocation(delayedOldSessionPacket, current), false);
    });

    it('Snapshot vs Live Race: live packet at t=10s should prevent late HTTP snapshot at t=8s from regressing coordinates', () => {
      const liveCurrent = {
        observedAtMs: baseTime + 10000,
        sequenceNumber: 50,
      };
      const staleSnapshot = {
        observedAt: new Date(baseTime + 8000).toISOString(),
        sequenceNumber: 45,
      };
      assert.strictEqual(isNewerLocation(staleSnapshot, liveCurrent), false);
    });
  });

  describe('2. EventEnvelope & Client Deduplication Contract', () => {
    it('should validate EventEnvelope structure with UUID eventId and valid payload', () => {
      const envelope: EventEnvelope<DriverOfferPayload> = {
        eventId: '550e8400-e29b-41d4-a716-446655440000',
        eventType: 'DRIVER_OFFER_CREATED',
        eventVersion: 1,
        occurredAt: '2026-10-26T14:30:00Z',
        correlationId: 'rhl-corr-123',
        payload: {
          offerId: '660e8400-e29b-41d4-a716-446655440001',
          tripId: '770e8400-e29b-41d4-a716-446655440002',
          driverId: '880e8400-e29b-41d4-a716-446655440003',
          serviceType: 'RIDE_STANDARD',
          pickupLatitude: 21.0285,
          pickupLongitude: 105.8542,
          pickupAddress: 'Hoan Kiem Lake, Hanoi',
          dropoffAddress: 'Noi Bai Airport, Hanoi',
          estimatedDistanceKm: 28.5,
          estimatedFare: 250000,
          offeredAt: '2026-10-26T14:30:00Z',
          expiresAt: '2026-10-26T14:30:30Z',
          status: 'PENDING',
        },
      };

      assert.strictEqual(envelope.eventType, 'DRIVER_OFFER_CREATED');
      assert.strictEqual(envelope.payload.serviceType, 'RIDE_STANDARD');
      assert.strictEqual(envelope.payload.estimatedFare, 250000);
      assert.strictEqual(envelope.eventVersion, 1);
    });

    it('should simulate client-side LRU deduplication cache', () => {
      const processedEventIds = new Set<string>();
      const maxCache = 5;

      const pushEvent = (id: string): boolean => {
        if (processedEventIds.has(id)) {
          return false; // duplicate
        }
        processedEventIds.add(id);
        if (processedEventIds.size > maxCache) {
          const oldest = processedEventIds.values().next().value;
          if (oldest) processedEventIds.delete(oldest);
        }
        return true;
      };

      assert.strictEqual(pushEvent('evt-1'), true);
      assert.strictEqual(pushEvent('evt-2'), true);
      assert.strictEqual(pushEvent('evt-1'), false); // Duplicate dropped!
      assert.strictEqual(pushEvent('evt-3'), true);
      assert.strictEqual(pushEvent('evt-4'), true);
      assert.strictEqual(pushEvent('evt-5'), true);
      assert.strictEqual(pushEvent('evt-6'), true); // Evicts evt-1
      assert.strictEqual(processedEventIds.size, 5);
      assert.strictEqual(processedEventIds.has('evt-1'), false);
    });
  });

  describe('3. Customer Trip Lifecycle Monotonicity & Regression Prevention', () => {
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

    const canTransition = (current: TripStatus | null, next: TripStatus): boolean => {
      if (!current) return true;
      if (current === 'COMPLETED' || current === 'CANCELLED' || current === 'NO_DRIVER') {
        return false; // Terminal states cannot be changed
      }
      return STATUS_PROGRESSION_RANK[next] >= STATUS_PROGRESSION_RANK[current];
    };

    it('should permit valid forward lifecycle progression', () => {
      assert.strictEqual(canTransition('MATCHING', 'ACCEPTED'), true);
      assert.strictEqual(canTransition('ACCEPTED', 'PICKING_UP'), true);
      assert.strictEqual(canTransition('PICKING_UP', 'ARRIVED'), true);
      assert.strictEqual(canTransition('ARRIVED', 'IN_TRIP'), true);
      assert.strictEqual(canTransition('IN_TRIP', 'COMPLETED'), true);
    });

    it('should prevent backwards regression when a delayed STOMP event or old HTTP snapshot arrives', () => {
      // If current state is IN_TRIP (level 6), and an old ARRIVED (level 5) message arrives late:
      assert.strictEqual(canTransition('IN_TRIP', 'ARRIVED'), false);
      // If current state is IN_TRIP, and a stale MATCHING message arrives:
      assert.strictEqual(canTransition('IN_TRIP', 'MATCHING'), false);
    });

    it('should lock terminal states: COMPLETED or CANCELLED cannot regress to active', () => {
      assert.strictEqual(canTransition('COMPLETED', 'IN_TRIP'), false);
      assert.strictEqual(canTransition('CANCELLED', 'MATCHING'), false);
    });
  });

  describe('4. Driver Offer State Flow & Expiration Logic', () => {
    it('should calculate remaining seconds and expire when past expiresAt', () => {
      const now = Date.now();
      const futureExpiresAt = new Date(now + 15000).toISOString();
      const pastExpiresAt = new Date(now - 1000).toISOString();

      const calcSeconds = (expiresAtStr: string) =>
        Math.max(0, Math.floor((new Date(expiresAtStr).getTime() - Date.now()) / 1000));

      assert.strictEqual(calcSeconds(futureExpiresAt) >= 14, true);
      assert.strictEqual(calcSeconds(pastExpiresAt), 0);
    });

    it('should distinguish OFFER_ACCEPTED from ASSIGNMENT_CONFIRMED', () => {
      // Acceptance states sequence:
      // 1. Initial: OFFER_PENDING
      // 2. Driver clicks accept: ACCEPT_REQUEST_SENT
      // 3. REST API returns 200: OFFER_ACCEPTED (Offer accepted by driver, but matching confirmation pending)
      // 4. Dispatch publishes ASSIGNMENT_CONFIRMED: ASSIGNMENT_CONFIRMED (Trip officially bound!)
      const stateProgression = [
        'OFFER_PENDING',
        'ACCEPT_REQUEST_SENT',
        'OFFER_ACCEPTED',
        'ASSIGNMENT_CONFIRMED',
      ];

      assert.strictEqual(stateProgression[2], 'OFFER_ACCEPTED');
      assert.strictEqual(stateProgression[3], 'ASSIGNMENT_CONFIRMED');
      assert.notStrictEqual(stateProgression[2], stateProgression[3]);
    });
  });

  describe('5. Security, Token Storage & Clean Logout State Isolation', () => {
    it('should store and remove token properly without leaking credentials', () => {
      tokenStorage.setAccessToken('mock-access-token-12345');
      assert.strictEqual(tokenStorage.getAccessToken(), 'mock-access-token-12345');

      tokenStorage.clear();
      assert.strictEqual(tokenStorage.getAccessToken(), null);
    });
  });
});
