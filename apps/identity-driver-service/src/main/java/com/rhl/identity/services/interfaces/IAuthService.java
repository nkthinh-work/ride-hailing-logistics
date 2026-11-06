package com.rhl.identity.services.interfaces;

import com.rhl.identity.dtos.*;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.UUID;

public interface IAuthService {

    RegisterResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    LoginResponse refresh(RefreshTokenRequest request);

    void logout(RefreshTokenRequest request);

    Map<String, Object> getMe(UUID userId, Jwt jwt);
}
