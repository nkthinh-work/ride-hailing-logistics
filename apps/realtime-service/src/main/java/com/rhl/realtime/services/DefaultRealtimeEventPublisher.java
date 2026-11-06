package com.rhl.realtime.services;

import com.rhl.realtime.models.DriverOfferPayload;
import com.rhl.realtime.models.EventEnvelope;
import com.rhl.realtime.models.TripStatusPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class DefaultRealtimeEventPublisher implements RealtimeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DefaultRealtimeEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    public DefaultRealtimeEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = Objects.requireNonNull(messagingTemplate, "messagingTemplate is required");
    }

    @Override
    public void sendOfferToDriver(UUID driverId, EventEnvelope<DriverOfferPayload> offerEvent) {
        Objects.requireNonNull(driverId, "driverId is required");
        Objects.requireNonNull(offerEvent, "offerEvent is required");

        log.info("Publishing DriverOffer event {} to driver user destination /user/{}/queue/offers",
                offerEvent.eventType(), driverId);
        messagingTemplate.convertAndSendToUser(
                driverId.toString(),
                "/queue/offers",
                offerEvent
        );
    }

    @Override
    public void sendTripUpdateToCustomer(UUID customerId, EventEnvelope<TripStatusPayload> tripEvent) {
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(tripEvent, "tripEvent is required");

        log.info("Publishing TripStatus event {} to customer user destination /user/{}/queue/trips",
                tripEvent.eventType(), customerId);
        messagingTemplate.convertAndSendToUser(
                customerId.toString(),
                "/queue/trips",
                tripEvent
        );
    }

    @Override
    public void sendNotificationToUser(UUID userId, EventEnvelope<?> notificationEvent) {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(notificationEvent, "notificationEvent is required");

        log.info("Publishing Notification event {} to user destination /user/{}/queue/notifications",
                notificationEvent.eventType(), userId);
        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/notifications",
                notificationEvent
        );
    }

    @Override
    public void broadcastTripLocation(UUID tripId, EventEnvelope<com.rhl.realtime.models.TripLocationPayload> locationEvent) {
        Objects.requireNonNull(tripId, "tripId is required");
        Objects.requireNonNull(locationEvent, "locationEvent is required");

        String destination = "/topic/trips." + tripId + ".location";
        log.info("Broadcasting TripLocation event {} to trip room {}",
                locationEvent.eventType(), destination);
        messagingTemplate.convertAndSend(destination, locationEvent);
    }
}
