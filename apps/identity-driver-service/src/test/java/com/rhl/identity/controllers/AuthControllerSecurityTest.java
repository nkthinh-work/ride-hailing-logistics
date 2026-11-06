package com.rhl.identity.controllers;

import com.rhl.identity.configs.SecurityConfig;
import com.rhl.identity.exceptions.GlobalExceptionHandler;
import com.rhl.identity.security.SecurityErrorHandler;
import com.rhl.identity.services.interfaces.IAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, GlobalExceptionHandler.class})
class AuthControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IAuthService authService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID userId = UUID.randomUUID();
    private final String email = "driver.verified@example.com";

    @Nested
    @DisplayName("GET /api/v1/auth/me Endpoint Tests")
    class GetMeTests {

        @Test
        @DisplayName("Authenticated user gets backend-confirmed profile including email, userId, and id")
        void getMe_whenAuthenticated_returnsFullProfile() throws Exception {
            when(authService.getMe(eq(userId), any(Jwt.class))).thenReturn(Map.of(
                    "userId", userId.toString(),
                    "id", userId.toString(),
                    "email", email,
                    "status", "ACTIVE",
                    "roles", List.of("DRIVER", "CUSTOMER"),
                    "permissions", List.of("TRIP_CREATE", "LOCATION_UPDATE")
            ));

            mockMvc.perform(get("/api/v1/auth/me")
                            .with(jwt().jwt(builder -> builder
                                    .subject(userId.toString())
                                    .claim("roles", List.of("DRIVER", "CUSTOMER"))
                                    .claim("permissions", List.of("TRIP_CREATE", "LOCATION_UPDATE"))
                            )))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(userId.toString()))
                    .andExpect(jsonPath("$.id").value(userId.toString()))
                    .andExpect(jsonPath("$.email").value(email))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.roles[0]").value("DRIVER"))
                    .andExpect(jsonPath("$.permissions[0]").value("TRIP_CREATE"));
        }

        @Test
        @DisplayName("Unauthenticated request to /api/v1/auth/me returns 401 Unauthorized")
        void getMe_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
