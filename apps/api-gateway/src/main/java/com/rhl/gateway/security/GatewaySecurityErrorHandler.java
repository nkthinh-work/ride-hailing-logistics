
package com.rhl.gateway.security;

import com.rhl.gateway.dto.ApiError;
import lombok.RequiredArgsConstructor;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class GatewaySecurityErrorHandler
        implements ServerAuthenticationEntryPoint,
        ServerAccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> commence(
            ServerWebExchange exchange,
            AuthenticationException ex) {

        String authorization = exchange.getRequest()
                .getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

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

        exchange.getResponse()
                .getHeaders()
                .set(HttpHeaders.WWW_AUTHENTICATE, challenge);

        return writeError(
                exchange,
                HttpStatus.UNAUTHORIZED,
                code,
                message
        );
    }

    @Override
    public Mono<Void> handle(
            ServerWebExchange exchange,
            AccessDeniedException ex) {

        return writeError(
                exchange,
                HttpStatus.FORBIDDEN,
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

    private Mono<Void> writeError(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message) {

        return Mono.defer(() -> {

            var response = exchange.getResponse();

            response.setStatusCode(status);
            response.getHeaders()
                    .setContentType(MediaType.APPLICATION_JSON);

            ApiError error = ApiError.of(
                    status.value(),
                    code,
                    message,
                    exchange.getRequest().getPath().value()
            );

            byte[] bytes = objectMapper.writeValueAsBytes(error);

            DataBuffer buffer = response.bufferFactory()
                    .wrap(bytes);

            return response.writeWith(Mono.just(buffer));
        });
    }
}
