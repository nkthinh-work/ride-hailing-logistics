package com.rhl.realtime.configs;

import com.rhl.realtime.security.StompAuthChannelInterceptor;
import com.rhl.realtime.security.StompSubscriptionInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Objects;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final RealtimeProperties properties;
    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final StompSubscriptionInterceptor stompSubscriptionInterceptor;

    public WebSocketConfig(
            RealtimeProperties properties,
            StompAuthChannelInterceptor stompAuthChannelInterceptor,
            StompSubscriptionInterceptor stompSubscriptionInterceptor
    ) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.stompAuthChannelInterceptor = Objects.requireNonNull(stompAuthChannelInterceptor, "stompAuthChannelInterceptor is required");
        this.stompSubscriptionInterceptor = Objects.requireNonNull(stompSubscriptionInterceptor, "stompSubscriptionInterceptor is required");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(properties.endpoint())
                .setAllowedOriginPatterns(properties.allowedOriginPatterns());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{properties.heartbeatOutgoingMs(), properties.heartbeatIncomingMs()})
                .setTaskScheduler(heartbeatTaskScheduler());

        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor, stompSubscriptionInterceptor);
    }

    @Override
    public boolean configureMessageConverters(java.util.List<org.springframework.messaging.converter.MessageConverter> messageConverters) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        org.springframework.messaging.converter.MappingJackson2MessageConverter converter =
                new org.springframework.messaging.converter.MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        messageConverters.add(converter);
        return false;
    }

    @Bean
    public TaskScheduler heartbeatTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
