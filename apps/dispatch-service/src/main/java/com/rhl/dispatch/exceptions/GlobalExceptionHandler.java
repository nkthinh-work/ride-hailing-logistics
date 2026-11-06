package com.rhl.dispatch.exceptions;

import com.rhl.dispatch.dtos.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DriverOfferNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            DriverOfferNotFoundException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        HttpStatus.NOT_FOUND.value(),
                        "OFFER_NOT_FOUND",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(OfferAccessDeniedException.class)
    public ResponseEntity<ApiError> handleOfferAccessDenied(
            OfferAccessDeniedException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        HttpStatus.FORBIDDEN.value(),
                        "OFFER_ACCESS_DENIED",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        HttpStatus.FORBIDDEN.value(),
                        "ACCESS_DENIED",
                        "You do not have permission to access this resource",
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(OfferExpiredException.class)
    public ResponseEntity<ApiError> handleOfferExpired(
            OfferExpiredException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        "OFFER_EXPIRED",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(InvalidOfferStateTransitionException.class)
    public ResponseEntity<ApiError> handleInvalidTransition(
            InvalidOfferStateTransitionException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        "INVALID_STATE_TRANSITION",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(DriverOfferConflictException.class)
    public ResponseEntity<ApiError> handleOfferConflict(
            DriverOfferConflictException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        ex.getErrorCode(),
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(
            OptimisticLockingFailureException ex,
            HttpServletRequest request) {

        log.warn("Optimistic locking failure at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        "CONCURRENT_MODIFICATION_CONFLICT",
                        "The offer was concurrently modified by another transaction. Please refresh and try again.",
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        String message = ex.getMessage();
        if (message != null && (message.contains("uk_driver_offers_single_winning_accepted")
                || message.contains("uk_driver_offers_single_winning_assigned"))) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiError.of(
                            HttpStatus.CONFLICT.value(),
                            "ANOTHER_DRIVER_ALREADY_ACCEPTED",
                            "Another driver has already accepted the trip offer.",
                            request.getRequestURI()
                    ));
        }

        if (message != null && message.contains("uk_matching_sessions_active_trip")) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiError.of(
                            HttpStatus.CONFLICT.value(),
                            "ACTIVE_MATCHING_SESSION_EXISTS",
                            "An active matching session is already coordinating this trip.",
                            request.getRequestURI()
                    ));
        }

        if (message != null && message.contains("uk_driver_offers_single_pending_per_driver")) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiError.of(
                            HttpStatus.CONFLICT.value(),
                            "DRIVER_ALREADY_HAS_PENDING_OFFER",
                            "Driver already has an active pending offer.",
                            request.getRequestURI()
                    ));
        }

        log.warn("Database constraint violation at {}: {}", request.getRequestURI(), message);

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        "DATABASE_CONSTRAINT_VIOLATION",
                        "Database constraint violation occurred.",
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
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Invalid request data");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "VALIDATION_ERROR",
                        message,
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "INVALID_PARAMETER",
                        "Invalid value for parameter: " + ex.getName(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalidJson(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "INVALID_JSON",
                        "Invalid JSON request body",
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "INVALID_ARGUMENT",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(LocationServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleLocationUnavailable(
            LocationServiceUnavailableException ex,
            HttpServletRequest request) {

        log.error("Location service unavailable at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "LOCATION_SERVICE_UNAVAILABLE",
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
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "IDENTITY_SERVICE_UNAVAILABLE",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(TripValidationException.class)
    public ResponseEntity<ApiError> handleTripValidation(
            TripValidationException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(ex.getStatus())
                .body(ApiError.of(
                        ex.getStatus(),
                        ex.getCode(),
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(TripServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleTripUnavailable(
            TripServiceUnavailableException ex,
            HttpServletRequest request) {

        log.error("Trip service unavailable at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "TRIP_SERVICE_UNAVAILABLE",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unexpected error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "INTERNAL_ERROR",
                        "An unexpected error occurred",
                        request.getRequestURI()
                ));
    }
}
