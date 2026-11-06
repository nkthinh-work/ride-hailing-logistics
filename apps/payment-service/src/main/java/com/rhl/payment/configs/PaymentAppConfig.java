package com.rhl.payment.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PaymentAppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
