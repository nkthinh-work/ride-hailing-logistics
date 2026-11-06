package com.rhl.identity.services.interfaces;

import com.rhl.identity.entities.User;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public interface IRefreshTokenService {
    String create(User user);

    User consume(String token);

    void revoke(String token);

    default String hash(String token) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] bytes = digest.digest(
                    token.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(bytes);

        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 is not available", ex
            );
        }
    }
}
