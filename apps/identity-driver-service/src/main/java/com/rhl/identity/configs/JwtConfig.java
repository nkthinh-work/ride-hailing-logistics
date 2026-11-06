
package com.rhl.identity.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import org.springframework.security.oauth2.jwt.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
public class JwtConfig {

    @Bean
    public RSAPrivateKey rsaPrivateKey(
            @Value("${security.jwt.private-key-location}")
            Resource resource,
            org.springframework.core.io.ResourceLoader resourceLoader) throws Exception {

        Resource targetResource = resource;
        if (!targetResource.exists()) {
            Resource candidate1 = resourceLoader.getResource("file:infra/keys/private.pem");
            if (candidate1.exists()) {
                targetResource = candidate1;
            } else {
                Resource candidate2 = resourceLoader.getResource("file:../../infra/keys/private.pem");
                if (candidate2.exists()) {
                    targetResource = candidate2;
                }
            }
        }

        byte[] bytes = readPem(targetResource, "PRIVATE KEY");

        KeyFactory factory = KeyFactory.getInstance("RSA");

        return (RSAPrivateKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(bytes)
        );
    }

    @Bean
    public RSAPublicKey rsaPublicKey(
            @Value("${security.jwt.public-key-location}")
            Resource resource) throws Exception {

        byte[] bytes = readPem(resource, "PUBLIC KEY");

        KeyFactory factory = KeyFactory.getInstance("RSA");

        return (RSAPublicKey) factory.generatePublic(
                new X509EncodedKeySpec(bytes)
        );
    }

    @Bean
    public JwtEncoder jwtEncoder(
            RSAPublicKey publicKey,
            RSAPrivateKey privateKey) {

        return NimbusJwtEncoder
                .withKeyPair(publicKey, privateKey)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            RSAPublicKey publicKey,
            @Value("${security.jwt.issuer}") String issuer) {

        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(publicKey).build();

        decoder.setJwtValidator(
                JwtValidators.createDefaultWithIssuer(issuer)
        );

        return decoder;
    }

    private byte[] readPem(
            Resource resource,
            String type) throws Exception {

        String content;

        try (var input = resource.getInputStream()) {
            content = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }

        String base64 = content
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");

        return Base64.getDecoder().decode(base64);
    }
}
