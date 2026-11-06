
package com.rhl.location.exceptions;

import com.rhl.location.dtos.ApiError;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import org.springframework.security.access.AccessDeniedException;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        403,
                        "ACCESS_DENIED",
                        "You do not have permission to perform this action",
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(DriverIneligibleException.class)
    public ResponseEntity<ApiError> handleDriverIneligible(
            DriverIneligibleException ex,
            HttpServletRequest request) {

        String message = ex.getReasons().isEmpty()
                ? ex.getMessage()
                : ex.getMessage() + ": " + String.join(", ", ex.getReasons());

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        403,
                        "DRIVER_NOT_ELIGIBLE",
                        message,
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(TelemetryValidationException.class)
    public ResponseEntity<ApiError> handleTelemetryValidation(
            TelemetryValidationException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .badRequest()
                .body(ApiError.of(
                        400,
                        ex.getErrorCode(),
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(OutOfOrderTelemetryException.class)
    public ResponseEntity<ApiError> handleOutOfOrderTelemetry(
            OutOfOrderTelemetryException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        409,
                        ex.getErrorCode(),
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(InvalidSessionException.class)
    public ResponseEntity<ApiError> handleInvalidSession(
            InvalidSessionException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        409,
                        ex.getErrorCode(),
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(InvalidVehicleSessionException.class)
    public ResponseEntity<ApiError> handleInvalidVehicleSession(
            InvalidVehicleSessionException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .badRequest()
                .body(ApiError.of(
                        400,
                        ex.getErrorCode(),
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(AmbiguousVehicleException.class)
    public ResponseEntity<ApiError> handleAmbiguousVehicle(
            AmbiguousVehicleException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .badRequest()
                .body(ApiError.of(
                        400,
                        ex.getErrorCode(),
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(IdentityServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleIdentityUnavailable(
            IdentityServiceUnavailableException ex,
            HttpServletRequest request) {

        log.error("Identity service unavailable at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(
                        503,
                        "IDENTITY_SERVICE_UNAVAILABLE",
                        "Unable to verify driver eligibility due to identity service unavailability",
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(DriverOfflineException.class)
    public ResponseEntity<ApiError> handleOffline(

            DriverOfflineException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        409,
                        "DRIVER_OFFLINE",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .badRequest()
                .body(ApiError.of(
                        400,
                        "INVALID_PARAMETER",
                        "Invalid value for parameter: " + ex.getName(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .badRequest()
                .body(ApiError.of(
                        400,
                        "INVALID_PARAMETER",
                        "Invalid coordinates or query parameters",
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getField() + ": "
                        + error.getDefaultMessage())
                .orElse("Invalid request data");

        return ResponseEntity
                .badRequest()
                .body(ApiError.of(
                        400,
                        "VALIDATION_ERROR",
                        message,
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalidBody(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .badRequest()
                .body(ApiError.of(
                        400,
                        "INVALID_JSON",
                        "Invalid JSON request body",
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ApiError> handleRedisUnavailable(
            RedisConnectionFailureException ex,
            HttpServletRequest request) {

        log.error(
                "Redis connection failed at {}",
                request.getRequestURI(),
                ex
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(
                        503,
                        "REDIS_UNAVAILABLE",
                        "Location service is temporarily unavailable",
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {

        log.error(
                "Unexpected error at {}",
                request.getRequestURI(),
                ex
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(
                        500,
                        "INTERNAL_ERROR",
                        "An unexpected error occurred",
                        request.getRequestURI()
                ));
    }
}
