package com.rhl.identity.dtos;

import java.util.Set;
import java.util.UUID;

public record RegisterResponse(
        UUID id,
        String email,
        Set<String> roles
) {
}