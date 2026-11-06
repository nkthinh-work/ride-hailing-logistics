package com.rhl.realtime.testutils;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class TestJwtHelper {

    private final KeyPair keyPair;
    private final String issuer;

    public TestJwtHelper(String issuer) {
        this.issuer = issuer;
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            this.keyPair = kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test RSA keypair", e);
        }
    }

    public RSAPublicKey getPublicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    public RSAPrivateKey getPrivateKey() {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    public String generateToken(UUID userId, List<String> roles, List<String> permissions, Instant expiresAt) {
        return generateToken(userId.toString(), roles, permissions, expiresAt, this.issuer, this.keyPair.getPrivate());
    }

    public String generateToken(String subject, List<String> roles, List<String> permissions, Instant expiresAt, String issuer, java.security.PrivateKey privateKey) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiresAt));

            if (roles != null) {
                builder.claim("roles", roles);
            }
            if (permissions != null) {
                builder.claim("permissions", permissions);
            }

            SignedJWT signedJWT = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                    builder.build()
            );

            signedJWT.sign(new RSASSASigner(privateKey));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test JWT", e);
        }
    }

    public String createDriverToken(UUID driverId) {
        return generateToken(
                driverId,
                List.of("DRIVER"),
                List.of("LOCATION_UPDATE", "DRIVER_OFFER_ACCEPT"),
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
    }

    public String createCustomerToken(UUID customerId) {
        return generateToken(
                customerId,
                List.of("CUSTOMER"),
                List.of("TRIP_CREATE", "TRIP_CANCEL"),
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
    }

    public String createExpiredToken(UUID userId) {
        return generateToken(
                userId,
                List.of("DRIVER"),
                List.of("LOCATION_UPDATE"),
                Instant.now().minus(10, ChronoUnit.MINUTES)
        );
    }

    public String createWrongIssuerToken(UUID userId) {
        return generateToken(
                userId.toString(),
                List.of("DRIVER"),
                List.of("LOCATION_UPDATE"),
                Instant.now().plus(30, ChronoUnit.MINUTES),
                "fake-issuer",
                this.keyPair.getPrivate()
        );
    }

    public String createUntrustedSignedToken(UUID userId) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair alienPair = kpg.generateKeyPair();
            return generateToken(
                    userId.toString(),
                    List.of("DRIVER"),
                    List.of("LOCATION_UPDATE"),
                    Instant.now().plus(30, ChronoUnit.MINUTES),
                    this.issuer,
                    alienPair.getPrivate()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
