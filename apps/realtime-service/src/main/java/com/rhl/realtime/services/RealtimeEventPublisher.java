package com.rhl.realtime.services;

import com.rhl.realtime.models.DriverOfferPayload;
import com.rhl.realtime.models.EventEnvelope;
import com.rhl.realtime.models.TripStatusPayload;

import java.util.UUID;

/**
 * Contract for publishing realtime events to user-specific STOMP queues.
 * Serves as the trusted boundary between backend services and the WebSocket transport tier.
 */
public interface RealtimeEventPublisher {

    /**
     * Sends a Driver Offer event to the driver's private queue: /user/{driverId}/queue/offers.
     */
    void sendOfferToDriver(UUID driverId, EventEnvelope<DriverOfferPayload> offerEvent);

    /**
     * Sends a Trip status update event to the customer's private queue: /user/{customerId}/queue/trips.
     */
    void sendTripUpdateToCustomer(UUID customerId, EventEnvelope<TripStatusPayload> tripEvent);

    /**
     * Sends a generic notification event to a user's private queue: /user/{userId}/queue/notifications.
     */
    void sendNotificationToUser(UUID userId, EventEnvelope<?> notificationEvent);

    /**
     * Broadcasts a live GPS location update to the dynamic trip room: /topic/trips.{tripId}.location.
     */
    void broadcastTripLocation(UUID tripId, EventEnvelope<com.rhl.realtime.models.TripLocationPayload> locationEvent);
}
