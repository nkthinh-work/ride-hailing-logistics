
package com.rhl.location.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
public class LocationJwtConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${security.jwt.public-key-location}")
            Resource resource,
            @Value("${security.jwt.issuer}")
            String issuer) throws Exception {

        String pem;

        try (var input = resource.getInputStream()) {
            pem = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }

        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] bytes = Base64.getDecoder().decode(base64);

        RSAPublicKey publicKey = (RSAPublicKey)
                KeyFactory.getInstance("RSA")
                        .generatePublic(
                                new X509EncodedKeySpec(bytes)
                        );

        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(publicKey).build();

        decoder.setJwtValidator(
                JwtValidators.createDefaultWithIssuer(issuer)
        );

        return decoder;
    }
}
