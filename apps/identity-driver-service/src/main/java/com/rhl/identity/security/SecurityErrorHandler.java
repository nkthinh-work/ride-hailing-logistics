
package com.rhl.identity.security;

import com.rhl.identity.dtos.ApiError;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class SecurityErrorHandler
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException ex) throws IOException {

        String authorization = request.getHeader(
                HttpHeaders.AUTHORIZATION
        );

        String code;
        String message;

        if (authorization == null || authorization.isBlank()) {
            code = "TOKEN_MISSING";
            message = "Access token is required";

        } else if (isExpired(ex)) {
            code = "TOKEN_EXPIRED";
            message = "Access token has expired";

        } else {
            code = "INVALID_TOKEN";
            message = "Invalid access token";
        }

        String challenge = code.equals("TOKEN_MISSING")
                ? "Bearer"
                : "Bearer error=\"invalid_token\"";

        response.setHeader(
                HttpHeaders.WWW_AUTHENTICATE,
                challenge
        );

        writeError(
                request,
                response,
                401,
                code,
                message
        );
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException ex) throws IOException {

        writeError(
                request,
                response,
                403,
                "ACCESS_DENIED",
                "You do not have permission"
        );
    }

    private boolean isExpired(Throwable ex) {

        Throwable cause = ex;

        while (cause != null) {

            if (cause instanceof JwtValidationException jwtEx) {

                return jwtEx.getErrors().stream()
                        .anyMatch(error -> {
                            String description = error.getDescription();

                            return description != null
                                    && description
                                    .toLowerCase(Locale.ROOT)
                                    .contains("jwt expired");
                        });
            }

            cause = cause.getCause();
        }

        return false;
    }

    private void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {

        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        ApiError error = ApiError.of(
                status,
                code,
                message,
                request.getRequestURI()
        );

        objectMapper.writeValue(
                response.getOutputStream(),
                error
        );
    }
}
