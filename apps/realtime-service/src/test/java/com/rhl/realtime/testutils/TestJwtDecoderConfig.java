package com.rhl.realtime.testutils;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@TestConfiguration
public class TestJwtDecoderConfig {

    private static final TestJwtHelper JWT_HELPER = new TestJwtHelper("rhl-identity");

    @Bean
    public TestJwtHelper testJwtHelper() {
        return JWT_HELPER;
    }

    @Bean
    @Primary
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(JWT_HELPER.getPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("rhl-identity"));
        return decoder;
    }
}
