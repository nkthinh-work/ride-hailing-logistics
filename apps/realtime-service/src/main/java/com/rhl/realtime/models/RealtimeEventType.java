package com.rhl.realtime.models;

/**
 * Realtime Event Types for M07 Realtime Transport Foundation.
 * Strictly maps to SRS section 5.6 and Master Plan M07 requirements.
 */
public enum RealtimeEventType {
    DRIVER_OFFER_CREATED,
    DRIVER_OFFER_REJECTED,
    DRIVER_OFFER_EXPIRED,
    DRIVER_OFFER_REVOKED,
    DRIVER_OFFER_ACCEPTED,
    DRIVER_OFFER_ASSIGNMENT_CONFIRMED,
    TRIP_STATUS_CHANGED,
    TRIP_CANCELLED,
    TRIP_COMPLETED,
    TRIP_DRIVER_ASSIGNED,
    TRIP_DRIVER_LOCATION_UPDATED
}
