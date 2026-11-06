package com.rhl.trip.clients;

import com.rhl.trip.dtos.QuoteValidationClientRequest;
import com.rhl.trip.dtos.QuoteValidationClientResponse;
import com.rhl.trip.exceptions.InvalidQuoteException;
import com.rhl.trip.exceptions.PricingServiceUnavailableException;
import com.rhl.trip.exceptions.QuoteAccessDeniedException;
import com.rhl.trip.exceptions.QuoteExpiredException;
import com.rhl.trip.exceptions.QuoteNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Machine-to-machine REST client for cross-service Quote Validation (M05 Group 2, FR-PRI-015).
 * Adheres strictly to the Fail-Closed security principle (cannot book with unverified quote).
 */
@Component
public class PricingQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(PricingQuoteClient.class);

    private final RestClient restClient;
    private final String internalSecret;

    @Autowired
    public PricingQuoteClient(
            @Value("${pricing.service.base-url:http://localhost:8083}") String baseUrl,
            @Value("${pricing.service.internal-secret:rhl-pricing-internal-secret-2026}") String internalSecret,
            @Value("${pricing.service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${pricing.service.read-timeout-ms:3000}") int readTimeoutMs,
            @Autowired(required = false) org.springframework.core.env.Environment env) {

        if (env != null && env.matchesProfiles("prod", "production", "staging")) {
            if (internalSecret == null || internalSecret.isBlank() || internalSecret.contains("internal-secret-2026") || internalSecret.contains("32chars")) {
                throw new IllegalStateException("Production and Staging profiles MUST configure a secure, non-default PRICING_INTERNAL_SECRET!");
            }
        }

        this.internalSecret = internalSecret;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public PricingQuoteClient(RestClient restClient, String internalSecret) {
        this.restClient = restClient;
        this.internalSecret = internalSecret;
    }

    /**
     * Validates quote with Pricing Service over authenticated internal API.
     *
     * @param request internal validation request
     * @return trusted immutable quote snapshot
     */
    public QuoteValidationClientResponse validateQuote(QuoteValidationClientRequest request) {
        log.debug("Calling Pricing Service for quote validation: quoteId={}, customerId={}",
                request.quoteId(), request.customerId());

        try {
            return restClient.post()
                    .uri("/internal/v1/fares/quotes/validate")
                    .header("X-Internal-Secret", internalSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, resp) -> {
                        throw new QuoteNotFoundException(request.quoteId());
                    })
                    .onStatus(status -> status.value() == 403, (req, resp) -> {
                        throw new QuoteAccessDeniedException(request.quoteId(), request.customerId());
                    })
                    .onStatus(status -> status.value() == 410 || status.value() == 422, (req, resp) -> {
                        throw new QuoteExpiredException(request.quoteId());
                    })
                    .onStatus(status -> status.value() == 400, (req, resp) -> {
                        String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new InvalidQuoteException("Quote validation rejected by pricing policy: " + body);
                    })
                    .onStatus(status -> status.is5xxServerError(), (req, resp) -> {
                        throw new PricingServiceUnavailableException(
                                "Pricing service returned server error: " + resp.getStatusCode());
                    })
                    .body(QuoteValidationClientResponse.class);

        } catch (QuoteNotFoundException | QuoteAccessDeniedException | QuoteExpiredException
                 | InvalidQuoteException | PricingServiceUnavailableException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            log.error("Failed to connect to Pricing Service: {}", ex.getMessage());
            throw new PricingServiceUnavailableException("Pricing service is unreachable or timed out", ex);
        } catch (Exception ex) {
            log.error("Unexpected error during quote validation: {}", ex.getMessage(), ex);
            throw new PricingServiceUnavailableException("Pricing service communication failure: " + ex.getMessage(), ex);
        }
    }
}
