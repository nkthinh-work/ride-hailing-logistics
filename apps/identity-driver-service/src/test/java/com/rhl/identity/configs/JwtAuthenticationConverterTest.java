package com.rhl.identity.configs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationConverterTest {

    private final SecurityConfig securityConfig = new SecurityConfig();
    private final JwtAuthenticationConverter converter = securityConfig.jwtAuthenticationConverter();

    @Test
    @DisplayName("Converts roles and permissions claims correctly into GrantedAuthorities")
    void convertsRolesAndPermissionsClaims() {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(600),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", "11111111-1111-1111-1111-111111111111",
                        "roles", List.of("REVIEWER", "CUSTOMER"),
                        "permissions", List.of("DRIVER_REVIEW", "TRIP_CREATE")
                )
        );

        AbstractAuthenticationToken authentication = converter.convert(jwt);
        assertThat(authentication).isNotNull();

        Collection<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertThat(authorities).contains(
                "ROLE_REVIEWER",
                "ROLE_CUSTOMER",
                "DRIVER_REVIEW",
                "TRIP_CREATE"
        );
    }

    @Test
    @DisplayName("Handles JWT with only roles and no permissions claim")
    void handlesRolesWithoutPermissions() {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(600),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", "11111111-1111-1111-1111-111111111111",
                        "roles", List.of("CUSTOMER")
                )
        );

        AbstractAuthenticationToken authentication = converter.convert(jwt);
        assertThat(authentication).isNotNull();

        Collection<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertThat(authorities).contains("ROLE_CUSTOMER");
        assertThat(authorities).doesNotContain("DRIVER_REVIEW");
    }
}
