package com.rhl.payment.security;

import com.rhl.payment.dtos.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Filter for securing internal machine-to-machine endpoints (/internal/**) via shared secret header.
 * Uses constant-time MessageDigest comparison to prevent timing side-channel attacks.
 */
public class InternalAuthFilter extends OncePerRequestFilter {

    private final String internalSecret;
    private final ObjectMapper objectMapper;

    public InternalAuthFilter(String internalSecret, ObjectMapper objectMapper) {
        this.internalSecret = internalSecret;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String secret = request.getHeader("X-Internal-Secret");

        if (secret == null || secret.isBlank()) {
            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                secret = authHeader.substring(7).trim();
            }
        }

        if (secret != null && isMatchingSecret(secret, internalSecret)) {
            PreAuthenticatedAuthenticationToken authentication =
                    new PreAuthenticatedAuthenticationToken(
                            "internal-service",
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"))
                    );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        ApiError error = ApiError.of(
                HttpServletResponse.SC_UNAUTHORIZED,
                "UNAUTHORIZED_INTERNAL_SERVICE",
                "Invalid or missing internal service secret",
                request.getRequestURI()
        );

        objectMapper.writeValue(response.getOutputStream(), error);
    }

    private boolean isMatchingSecret(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }
}
