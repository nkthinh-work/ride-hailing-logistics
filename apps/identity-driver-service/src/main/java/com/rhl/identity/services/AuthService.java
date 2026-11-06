
package com.rhl.identity.services;

import com.rhl.identity.dtos.*;
import com.rhl.identity.entities.*;
import com.rhl.identity.exceptions.*;
import com.rhl.identity.repositories.*;
import com.rhl.identity.services.interfaces.IAuthService;

import com.rhl.identity.services.interfaces.IRefreshTokenService;
import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService implements IAuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final IRefreshTokenService iRefreshTokenService;

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {

        String email = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyExistsException();
        }

        Role customerRole = roleRepository
                .findByCode("CUSTOMER")
                .orElseThrow(() ->
                        new IllegalStateException(
                                "CUSTOMER role not configured"
                        )
                );

        String hash = passwordEncoder.encode(request.password());

        User user = new User(email, hash);
        user.addRole(customerRole);

        userRepository.save(user);

        return new RegisterResponse(
                user.getId(),
                user.getEmail(),
                Set.of(customerRole.getCode())
        );
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {

        String email = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        User user = userRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(
                request.password(),
                user.getPasswordHash()
        ) || !"ACTIVE".equals(user.getStatus())) {
            throw new InvalidCredentialsException();
        }

        return issueTokens(user);
    }

    @Override
    @Transactional
    public LoginResponse refresh(RefreshTokenRequest request) {

        User user = iRefreshTokenService.consume(
                request.refreshToken()
        );

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new InvalidRefreshTokenException();
        }

        return issueTokens(user);
    }

    @Override
    @Transactional
    public void logout(RefreshTokenRequest request) {

        iRefreshTokenService.revoke(
                request.refreshToken()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getMe(UUID userId, Jwt jwt) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId.toString());
        response.put("id", userId.toString());
        response.put("roles", jwt != null ? jwt.getClaimAsStringList("roles") : List.of());
        response.put("permissions", jwt != null ? jwt.getClaimAsStringList("permissions") : List.of());

        userRepository.findById(userId).ifPresent(user -> {
            response.put("email", user.getEmail());
            response.put("status", user.getStatus());
            if (user.getCreatedAt() != null) {
                response.put("createdAt", user.getCreatedAt().toString());
            }
        });

        if (!response.containsKey("email") && jwt != null && jwt.hasClaim("email")) {
            response.put("email", jwt.getClaimAsString("email"));
        }

        return response;
    }

    private LoginResponse issueTokens(User user) {

        Set<String> roles = user.getRoles()
                .stream()
                .map(Role::getCode)
                .collect(Collectors.toSet());

        Set<String> permissions = user.getRoles()
                .stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getCode)
                .collect(Collectors.toSet());

        String accessToken = jwtService.generateToken(
                user.getId(),
                user.getEmail(),
                roles,
                permissions
        );

        String refreshToken = iRefreshTokenService.create(user);

        return new LoginResponse(
                accessToken,
                refreshToken,
                "Bearer",
                600
        );
    }
}
