
package com.rhl.identity.services;

import com.rhl.identity.services.interfaces.IJwtService;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService implements IJwtService {

    private final JwtEncoder jwtEncoder;

    @Value("${security.jwt.issuer}")
    private String issuer;

    @Override
    public String generateToken(
            UUID userId,
            Set<String> roles,
            Set<String> permissions) {
        return generateToken(userId, null, roles, permissions);
    }

    @Override
    public String generateToken(
            UUID userId,
            String email,
            Set<String> roles,
            Set<String> permissions) {

        Instant now = Instant.now();

        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .claim("roles", roles)
                .claim("permissions", permissions);

        if (email != null && !email.isBlank()) {
            builder.claim("email", email);
        }

        JwtClaimsSet claims = builder.build();

        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .build();

        return jwtEncoder
                .encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();
    }
}
