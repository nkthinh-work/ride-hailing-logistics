package com.rhl.realtime.security;

import com.rhl.realtime.configs.JwtConfig;
import com.rhl.realtime.services.RealtimeSessionRegistry;
import com.rhl.realtime.testutils.TestJwtHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("STOMP JWT Authentication & Authorization Unit Tests")
class StompJwtAuthenticationTest {

    private static final String ISSUER = "rhl-identity";

    private TestJwtHelper jwtHelper;
    private StompAuthChannelInterceptor authInterceptor;
    private StompSubscriptionInterceptor subscriptionInterceptor;
    private RealtimeSessionRegistry sessionRegistry;
    private com.rhl.realtime.services.TripMembershipProvider membershipProvider;
    private com.rhl.realtime.services.TripRoomAuthorizationService tripRoomAuthService;
    private MessageChannel dummyChannel;

    @BeforeEach
    void setUp() {
        jwtHelper = new TestJwtHelper(ISSUER);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtHelper.getPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));

        JwtAuthenticationConverter converter = new JwtConfig().jwtAuthenticationConverter();
        sessionRegistry = new RealtimeSessionRegistry();

        membershipProvider = mock(com.rhl.realtime.services.TripMembershipProvider.class);
        tripRoomAuthService = new com.rhl.realtime.services.TripRoomAuthorizationService(membershipProvider);

        authInterceptor = new StompAuthChannelInterceptor(decoder, converter, sessionRegistry);
        subscriptionInterceptor = new StompSubscriptionInterceptor(tripRoomAuthService);
        dummyChannel = mock(MessageChannel.class);
    }

    @Nested
    @DisplayName("1. STOMP CONNECT Authentication Matrix (P0)")
    class ConnectAuthenticationTests {

        @Test
        @DisplayName("Valid DRIVER token succeeds: authenticates and sets Principal with ROLE_DRIVER")
        void validDriverToken_succeeds() {
            UUID driverId = UUID.randomUUID();
            String token = jwtHelper.createDriverToken(driverId);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-001");
            accessor.setNativeHeader("Authorization", "Bearer " + token);
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            Message<?> result = authInterceptor.preSend(message, dummyChannel);
            assertThat(result).isNotNull();

            StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
            Authentication auth = (Authentication) resultAccessor.getUser();
            assertThat(auth).isNotNull();
            assertThat(auth.isAuthenticated()).isTrue();
            assertThat(auth.getName()).isEqualTo(driverId.toString());

            List<String> authorityStrings = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
            assertThat(authorityStrings).contains("ROLE_DRIVER", "LOCATION_UPDATE", "DRIVER_OFFER_ACCEPT");
            assertThat(authorityStrings).doesNotContain("ROLE_INTERNAL_SERVICE");

            assertThat(sessionRegistry.isUserConnected(driverId)).isTrue();
            assertThat(sessionRegistry.getActiveSessionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Valid CUSTOMER token succeeds: authenticates and sets Principal with ROLE_CUSTOMER")
        void validCustomerToken_succeeds() {
            UUID customerId = UUID.randomUUID();
            String token = jwtHelper.createCustomerToken(customerId);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-002");
            accessor.setNativeHeader("Authorization", "Bearer " + token);
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            Message<?> result = authInterceptor.preSend(message, dummyChannel);
            assertThat(result).isNotNull();

            StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
            Authentication auth = (Authentication) resultAccessor.getUser();
            assertThat(auth).isNotNull();
            assertThat(auth.getName()).isEqualTo(customerId.toString());
            assertThat(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList())
                    .contains("ROLE_CUSTOMER", "TRIP_CREATE", "TRIP_CANCEL");

            assertThat(sessionRegistry.isUserConnected(customerId)).isTrue();
        }

        @Test
        @DisplayName("Missing Authorization header rejects STOMP CONNECT")
        void missingToken_rejected() {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-003");
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Missing Bearer token");
        }

        @Test
        @DisplayName("Invalid JWT signature rejects STOMP CONNECT")
        void invalidSignature_rejected() {
            UUID driverId = UUID.randomUUID();
            String token = jwtHelper.createUntrustedSignedToken(driverId);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-004");
            accessor.setNativeHeader("Authorization", "Bearer " + token);
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Invalid or expired JWT token");
        }

        @Test
        @DisplayName("Expired JWT token rejects STOMP CONNECT")
        void expiredToken_rejected() {
            UUID driverId = UUID.randomUUID();
            String token = jwtHelper.createExpiredToken(driverId);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-005");
            accessor.setNativeHeader("Authorization", "Bearer " + token);
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Invalid or expired JWT token");
        }

        @Test
        @DisplayName("Wrong issuer rejects STOMP CONNECT")
        void wrongIssuer_rejected() {
            UUID driverId = UUID.randomUUID();
            String token = jwtHelper.createWrongIssuerToken(driverId);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-006");
            accessor.setNativeHeader("Authorization", "Bearer " + token);
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Invalid or expired JWT token");
        }

        @Test
        @DisplayName("Malformed Authorization token rejects STOMP CONNECT")
        void malformedToken_rejected() {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-007");
            accessor.setNativeHeader("Authorization", "Bearer not.a.valid.jwt");
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Invalid or expired JWT token");
        }

        @Test
        @DisplayName("Non-UUID subject rejects STOMP CONNECT")
        void nonUuidSubject_rejected() {
            String token = jwtHelper.generateToken(
                    "not-a-uuid-attacker",
                    List.of("DRIVER"),
                    List.of("LOCATION_UPDATE"),
                    Instant.now().plus(10, ChronoUnit.MINUTES),
                    ISSUER,
                    jwtHelper.getPrivateKey()
            );

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-008");
            accessor.setNativeHeader("Authorization", "Bearer " + token);
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Invalid JWT subject: must be a valid UUID");
        }

        @Test
        @DisplayName("Token attempting to claim ROLE_INTERNAL_SERVICE is rejected on WebSocket")
        void internalRoleOverWebSocket_rejected() {
            UUID userId = UUID.randomUUID();
            String token = jwtHelper.generateToken(
                    userId.toString(),
                    List.of("INTERNAL_SERVICE"),
                    List.of(),
                    Instant.now().plus(10, ChronoUnit.MINUTES),
                    ISSUER,
                    jwtHelper.getPrivateKey()
            );

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-009");
            accessor.setNativeHeader("Authorization", "Bearer " + token);
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("ROLE_INTERNAL_SERVICE is not allowed on WebSocket transport");
        }

        @Test
        @DisplayName("STOMP DISCONNECT cleans up active session registry")
        void disconnect_removesSessionFromRegistry() {
            UUID driverId = UUID.randomUUID();
            String token = jwtHelper.createDriverToken(driverId);

            StompHeaderAccessor connectAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            connectAccessor.setSessionId("sess-disc");
            connectAccessor.setNativeHeader("Authorization", "Bearer " + token);
            authInterceptor.preSend(MessageBuilder.createMessage(new byte[0], connectAccessor.getMessageHeaders()), dummyChannel);
            assertThat(sessionRegistry.isUserConnected(driverId)).isTrue();

            StompHeaderAccessor discAccessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
            discAccessor.setSessionId("sess-disc");
            authInterceptor.preSend(MessageBuilder.createMessage(new byte[0], discAccessor.getMessageHeaders()), dummyChannel);

            assertThat(sessionRegistry.isUserConnected(driverId)).isFalse();
            assertThat(sessionRegistry.getActiveSessionCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("2. STOMP SUBSCRIBE Authorization Matrix (P0)")
    class SubscriptionAuthorizationTests {

        private Authentication driverAuth;
        private Authentication customerAuth;

        @BeforeEach
        void authenticateUsers() {
            UUID driverId = UUID.randomUUID();
            String driverToken = jwtHelper.createDriverToken(driverId);
            StompHeaderAccessor dAcc = StompHeaderAccessor.create(StompCommand.CONNECT);
            dAcc.setSessionId("driver-sess");
            dAcc.setNativeHeader("Authorization", "Bearer " + driverToken);
            Message<?> dMsg = authInterceptor.preSend(MessageBuilder.createMessage(new byte[0], dAcc.getMessageHeaders()), dummyChannel);
            driverAuth = (Authentication) StompHeaderAccessor.wrap(dMsg).getUser();

            UUID customerId = UUID.randomUUID();
            String customerToken = jwtHelper.createCustomerToken(customerId);
            StompHeaderAccessor cAcc = StompHeaderAccessor.create(StompCommand.CONNECT);
            cAcc.setSessionId("customer-sess");
            cAcc.setNativeHeader("Authorization", "Bearer " + customerToken);
            Message<?> cMsg = authInterceptor.preSend(MessageBuilder.createMessage(new byte[0], cAcc.getMessageHeaders()), dummyChannel);
            customerAuth = (Authentication) StompHeaderAccessor.wrap(cMsg).getUser();
        }

        @Test
        @DisplayName("DRIVER can subscribe to /user/queue/offers and /user/queue/offer-updates")
        void driver_subscribesToOffers_allowed() {
            StompHeaderAccessor subOffers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            subOffers.setUser(driverAuth);
            subOffers.setDestination("/user/queue/offers");
            Message<?> result1 = subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], subOffers.getMessageHeaders()), dummyChannel);
            assertThat(result1).isNotNull();

            StompHeaderAccessor subUpdates = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            subUpdates.setUser(driverAuth);
            subUpdates.setDestination("/user/queue/offer-updates");
            Message<?> result2 = subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], subUpdates.getMessageHeaders()), dummyChannel);
            assertThat(result2).isNotNull();
        }

        @Test
        @DisplayName("CUSTOMER can subscribe to /user/queue/trips")
        void customer_subscribesToTrips_allowed() {
            StompHeaderAccessor subTrips = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            subTrips.setUser(customerAuth);
            subTrips.setDestination("/user/queue/trips");
            Message<?> result = subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], subTrips.getMessageHeaders()), dummyChannel);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("CUSTOMER is FORBIDDEN from subscribing to /user/queue/offers")
        void customer_subscribesToOffers_forbidden() {
            StompHeaderAccessor subOffers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            subOffers.setUser(customerAuth);
            subOffers.setDestination("/user/queue/offers");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], subOffers.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Only DRIVER can subscribe to driver offer channels");
        }

        @Test
        @DisplayName("DRIVER is FORBIDDEN from subscribing to /user/queue/trips")
        void driver_subscribesToTrips_forbidden() {
            StompHeaderAccessor subTrips = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            subTrips.setUser(driverAuth);
            subTrips.setDestination("/user/queue/trips");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], subTrips.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Only CUSTOMER can subscribe to customer trip channels");
        }

        @Test
        @DisplayName("Unauthenticated session is FORBIDDEN from subscribing to any queue")
        void unauthenticated_subscribes_forbidden() {
            StompHeaderAccessor subTrips = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            subTrips.setDestination("/user/queue/trips");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], subTrips.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Unauthenticated subscription is forbidden");
        }

        @Test
        @DisplayName("Direct subscription to raw broker /queue/** destination is FORBIDDEN")
        void rawBrokerQueue_subscriptionForbidden() {
            StompHeaderAccessor subRaw = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            subRaw.setUser(driverAuth);
            subRaw.setDestination("/queue/offers-other-driver");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], subRaw.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Direct subscription to broker queue is forbidden");
        }

        @Test
        @DisplayName("Subscription to public /topic/** is FORBIDDEN (no unpartitioned broadcasts)")
        void publicTopic_subscriptionForbidden() {
            StompHeaderAccessor subTopic = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            subTopic.setUser(driverAuth);
            subTopic.setDestination("/topic/all-offers");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], subTopic.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Subscription to public topic is forbidden");
        }

        @Test
        @DisplayName("Customer Owner CAN subscribe to active Trip Room (/topic/trips.{tripId}.location)")
        void customerOwner_subscribesToTripRoom_allowed() {
            UUID tripId = UUID.randomUUID();
            UUID custId = UUID.fromString(customerAuth.getName());
            UUID drvId = UUID.randomUUID();

            when(membershipProvider.getTripMembership(tripId)).thenReturn(java.util.Optional.of(
                    new com.rhl.realtime.models.TripMembershipInfo(tripId, custId, drvId, "IN_TRIP")
            ));

            StompHeaderAccessor sub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            sub.setUser(customerAuth);
            sub.setDestination("/topic/trips." + tripId + ".location");

            Message<?> result = subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], sub.getMessageHeaders()), dummyChannel);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Assigned Driver CAN subscribe to active Trip Room (/topic/trips.{tripId}.location)")
        void assignedDriver_subscribesToTripRoom_allowed() {
            UUID tripId = UUID.randomUUID();
            UUID custId = UUID.randomUUID();
            UUID drvId = UUID.fromString(driverAuth.getName());

            when(membershipProvider.getTripMembership(tripId)).thenReturn(java.util.Optional.of(
                    new com.rhl.realtime.models.TripMembershipInfo(tripId, custId, drvId, "PICKING_UP")
            ));

            StompHeaderAccessor sub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            sub.setUser(driverAuth);
            sub.setDestination("/topic/trips." + tripId + ".location");

            Message<?> result = subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], sub.getMessageHeaders()), dummyChannel);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Outsider Customer is FORBIDDEN from subscribing to unowned Trip Room")
        void outsiderCustomer_subscribesToTripRoom_forbidden() {
            UUID tripId = UUID.randomUUID();
            UUID otherCustId = UUID.randomUUID();
            UUID drvId = UUID.randomUUID();

            when(membershipProvider.getTripMembership(tripId)).thenReturn(java.util.Optional.of(
                    new com.rhl.realtime.models.TripMembershipInfo(tripId, otherCustId, drvId, "IN_TRIP")
            ));

            StompHeaderAccessor sub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            sub.setUser(customerAuth);
            sub.setDestination("/topic/trips." + tripId + ".location");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], sub.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("not an authorized member");
        }

        @Test
        @DisplayName("Outsider Driver is FORBIDDEN from subscribing to unassigned Trip Room")
        void outsiderDriver_subscribesToTripRoom_forbidden() {
            UUID tripId = UUID.randomUUID();
            UUID custId = UUID.randomUUID();
            UUID otherDrvId = UUID.randomUUID();

            when(membershipProvider.getTripMembership(tripId)).thenReturn(java.util.Optional.of(
                    new com.rhl.realtime.models.TripMembershipInfo(tripId, custId, otherDrvId, "IN_TRIP")
            ));

            StompHeaderAccessor sub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            sub.setUser(driverAuth);
            sub.setDestination("/topic/trips." + tripId + ".location");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], sub.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("not an authorized member");
        }

        @Test
        @DisplayName("Wildcard trip room subscription (/topic/trips.*) is strictly FORBIDDEN")
        void wildcardTripRoom_subscriptionForbidden() {
            StompHeaderAccessor sub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            sub.setUser(customerAuth);
            sub.setDestination("/topic/trips.*.location");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], sub.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("wildcard subscription forbidden");
        }

        @Test
        @DisplayName("Non-streamable Trip (COMPLETED or CANCELLED) subscription is FORBIDDEN")
        void terminalTrip_subscriptionForbidden() {
            UUID tripId = UUID.randomUUID();
            UUID custId = UUID.fromString(customerAuth.getName());
            UUID drvId = UUID.randomUUID();

            when(membershipProvider.getTripMembership(tripId)).thenReturn(java.util.Optional.of(
                    new com.rhl.realtime.models.TripMembershipInfo(tripId, custId, drvId, "COMPLETED")
            ));

            StompHeaderAccessor sub = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            sub.setUser(customerAuth);
            sub.setDestination("/topic/trips." + tripId + ".location");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], sub.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("not active for live tracking");
        }
    }

    @Nested
    @DisplayName("3. STOMP SEND Authorization Matrix (P0)")
    class SendAuthorizationTests {

        @Test
        @DisplayName("Client attempting to publish directly to broker /topic or /queue is FORBIDDEN")
        void clientSend_toBroker_forbidden() {
            StompHeaderAccessor sendAcc = StompHeaderAccessor.create(StompCommand.SEND);
            sendAcc.setDestination("/topic/trips/123");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], sendAcc.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Client publishing is forbidden");
        }

        @Test
        @DisplayName("Client attempting to publish fake system event to /app destination is FORBIDDEN")
        void clientSend_toApp_forbidden() {
            StompHeaderAccessor sendAcc = StompHeaderAccessor.create(StompCommand.SEND);
            sendAcc.setDestination("/app/driver-offer-created");

            assertThatThrownBy(() -> subscriptionInterceptor.preSend(MessageBuilder.createMessage(new byte[0], sendAcc.getMessageHeaders()), dummyChannel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Client publishing is forbidden");
        }
    }
}
