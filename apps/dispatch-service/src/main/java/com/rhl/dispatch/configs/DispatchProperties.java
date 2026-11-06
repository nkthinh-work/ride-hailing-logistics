package com.rhl.dispatch.configs;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dispatch.offer")
public record DispatchProperties(
        int defaultTtlSeconds,
        int maxRetryRounds
) {
    public DispatchProperties {
        if (defaultTtlSeconds <= 0) {
            defaultTtlSeconds = 15;
        }
        if (maxRetryRounds <= 0) {
            maxRetryRounds = 3;
        }
    }
}
