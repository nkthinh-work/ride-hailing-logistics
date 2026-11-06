
package com.rhl.location.dtos;

import java.time.Instant;

public record ApiError(
        int status,
        String code,
        String message,
        String path,
        Instant timestamp
) {

    public static ApiError of(
            int status,
            String code,
            String message,
            String path) {

        return new ApiError(
                status,
                code,
                message,
                path,
                Instant.now()
        );
    }
}
