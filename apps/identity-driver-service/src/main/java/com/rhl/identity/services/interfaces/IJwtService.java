package com.rhl.identity.services.interfaces;

import java.util.Set;
import java.util.UUID;

public interface IJwtService {
    String generateToken(
            UUID userId,
            Set<String> roles,
            Set<String> permissions);

    default String generateToken(
            UUID userId,
            String email,
            Set<String> roles,
            Set<String> permissions) {
        return generateToken(userId, roles, permissions);
    }
}
