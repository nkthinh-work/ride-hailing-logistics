package com.rhl.pricing.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * General configuration providing injectable Clock bean for deterministic testing.
 */
@Configuration
public class PricingConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
