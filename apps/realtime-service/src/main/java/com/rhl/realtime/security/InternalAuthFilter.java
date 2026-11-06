package com.rhl.realtime.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public class InternalAuthFilter extends OncePerRequestFilter {

    public static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";
    private final String expectedSecret;

    public InternalAuthFilter(String expectedSecret) {
        this.expectedSecret = expectedSecret;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (!path.startsWith("/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedSecret = request.getHeader(INTERNAL_SECRET_HEADER);
        if (providedSecret == null || !MessageDigest.isEqual(
                providedSecret.getBytes(StandardCharsets.UTF_8),
                expectedSecret.getBytes(StandardCharsets.UTF_8)
        )) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"ACCESS_DENIED\",\"message\":\"Invalid internal secret\"}");
            return;
        }

        String callerService = request.getHeader("X-Caller-Service");
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"));

        if (callerService != null && !callerService.isBlank()) {
            String sanitizedCaller = callerService.trim().toUpperCase().replace('-', '_');
            authorities.add(new SimpleGrantedAuthority("ROLE_CALLER_" + sanitizedCaller));
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        callerService != null ? callerService : "internal-service",
                        null,
                        authorities
                );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }
}
