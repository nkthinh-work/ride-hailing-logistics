
package com.rhl.identity.services;

import com.rhl.identity.entities.RefreshSession;
import com.rhl.identity.entities.User;
import com.rhl.identity.exceptions.InvalidRefreshTokenException;
import com.rhl.identity.repositories.RefreshSessionRepository;

import com.rhl.identity.services.interfaces.IRefreshTokenService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class RefreshTokenService implements IRefreshTokenService {

    private final RefreshSessionRepository repository;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String create(User user) {

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        RefreshSession session = new RefreshSession(
                user,
                hash(token),
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        repository.save(session);

        return token;
    }

    @Override
    public User consume(String token) {

        RefreshSession session = repository
                .findByHashForUpdate(hash(token))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (!session.isValid()) {
            throw new InvalidRefreshTokenException();
        }

        session.revoke();

        return session.getUser();
    }

    @Override
    public void revoke(String token) {

        repository.findByHashForUpdate(hash(token))
                .ifPresent(RefreshSession::revoke);
    }

}
