package com.rhl.dispatch.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

@Component
public class RealtimePublishClient {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublishClient.class);

    private final RestClient restClient;

    @Autowired
    public RealtimePublishClient(
            @Value("${realtime.service.base-url:http://localhost:8086}") String baseUrl,
            @Value("${realtime.service.internal-secret:rhl-internal-secret-2026}") String internalSecret,
            @Value("${realtime.service.timeout-ms:2000}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Secret", internalSecret)
                .defaultHeader("X-Caller-Service", "dispatch-service")
                .build();
    }

    public RealtimePublishClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public void publishOffer(UUID driverId, Object envelope) {
        log.debug("Publishing driver offer to driver {}", driverId);
        restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/v1/realtime/publish/offer")
                        .queryParam("driverId", driverId)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(envelope)
                .retrieve()
                .toBodilessEntity();
    }
}
