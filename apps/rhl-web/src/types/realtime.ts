// Realtime & Messaging Types for M07 Realtime Foundation

export type RealtimeEventType =
  | 'DRIVER_OFFER_CREATED'
  | 'DRIVER_OFFER_REJECTED'
  | 'DRIVER_OFFER_EXPIRED'
  | 'DRIVER_OFFER_REVOKED'
  | 'DRIVER_OFFER_ACCEPTED'
  | 'DRIVER_OFFER_ASSIGNMENT_CONFIRMED'
  | 'TRIP_STATUS_CHANGED'
  | 'TRIP_CANCELLED'
  | 'TRIP_COMPLETED'
  | 'TRIP_DRIVER_ASSIGNED'
  | 'TRIP_DRIVER_LOCATION_UPDATED';

export type RealtimeConnectionStatus =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'RECONNECTING'
  | 'AUTH_ERROR';

export type TripStatus =
  | 'CREATED'
  | 'MATCHING'
  | 'ACCEPTED'
  | 'PICKING_UP'
  | 'ARRIVED'
  | 'IN_TRIP'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_DRIVER';

export type OfferStatus =
  | 'PENDING'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'REVOKED';

export type AssignmentStatus =
  | 'OFFER_PENDING'
  | 'ACCEPT_REQUEST_SENT'
  | 'OFFER_ACCEPTED'
  | 'ASSIGNMENT_PENDING'
  | 'ASSIGNMENT_CONFIRMED'
  | 'ASSIGNMENT_FAILED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'REVOKED';

/**
 * Standard Realtime Message Envelope Contract (SRS COM-007, COM-009, NFR-MNT-001).
 */
export interface EventEnvelope<T> {
  eventId: string;
  eventType: RealtimeEventType;
  eventVersion: number;
  occurredAt: string;
  correlationId?: string;
  entityId?: string;
  payload: T;
}

/**
 * Driver Offer payload contract broadcasted to /user/queue/offers.
 */
export interface DriverOfferPayload {
  offerId: string;
  tripId: string;
  driverId: string;
  serviceType: string;
  pickupLatitude: number;
  pickupLongitude: number;
  pickupAddress: string;
  dropoffAddress: string;
  estimatedDistanceKm?: number | null;
  estimatedFare?: number | null;
  offeredAt: string;
  expiresAt: string;
  status: string;
}

/**
 * Trip status update payload broadcasted to /user/queue/trips.
 */
export interface TripStatusPayload {
  tripId: string;
  customerId: string;
  driverId?: string | null;
  status: TripStatus;
  actor?: string | null;
  reason?: string | null;
  occurredAt: string;
  sequence?: number | null;
}

/**
 * Live Driver GPS payload broadcasted to /topic/trips.{tripId}.location.
 */
export interface TripLocationPayload {
  tripId: string;
  driverId: string;
  latitude: number;
  longitude: number;
  headingDegrees?: number | null;
  speedMetersPerSecond?: number | null;
  sequenceNumber?: number | null;
  observedAt: string;
}

/**
 * REST response from Dispatch Service GET /api/v1/offers/pending or /{offerId}.
 */
export interface DriverOfferResponse {
  id: string;
  tripId: string;
  driverId: string;
  vehicleId?: string | null;
  serviceType: string;
  status: OfferStatus;
  offeredAt: string;
  expiresAt: string;
  acceptedAt?: string | null;
  rejectedAt?: string | null;
  rejectionReason?: string | null;
  revokedAt?: string | null;
  revocationReason?: string | null;
  version: number;
}

/**
 * REST response from Trip Service GET /api/v1/trips/{tripId}.
 */
export interface TripResponse {
  tripId: string;
  customerId: string;
  driverId?: string | null;
  status: TripStatus;
  quoteId?: string;
  idempotencyKey?: string;
  serviceType: string;
  vehicleType?: string;
  pickupLatitude: number;
  pickupLongitude: number;
  pickupAddress: string;
  dropoffLatitude: number;
  dropoffLongitude: number;
  dropoffAddress: string;
  estimatedDistanceKm?: number;
  estimatedDurationMinutes?: number;
  estimatedFare?: number;
  surgeMultiplier?: number;
  currency?: string;
  pricingRuleVersion?: string;
  requestedAt: string;
  acceptedAt?: string | null;
  arrivedAt?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  cancelledAt?: string | null;
  cancellationReason?: string | null;
  cancelledBy?: string | null;
  cancellationActorRole?: string | null;
  cancellationFee?: number | null;
  version: number;
}

/**
 * REST response from Trip Service GET /api/v1/trips/{tripId}/location.
 */
export interface TripLocationSnapshotResponse {
  tripId: string;
  driverId?: string | null;
  status: string;
  latitude?: number | null;
  longitude?: number | null;
  recordedAt?: string | null;
  available: boolean;
}

export interface RejectOfferRequest {
  reason?: string;
}
