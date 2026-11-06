package com.rhl.trip.exceptions;

import com.rhl.trip.dtos.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @ExceptionHandler(ActiveTripConflictException.class)
    public ResponseEntity<ApiError> handleActiveTripConflict(
            ActiveTripConflictException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        "ACTIVE_TRIP_CONFLICT",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(QuoteReplayConflictException.class)
    public ResponseEntity<ApiError> handleQuoteReplayConflict(
            QuoteReplayConflictException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        "QUOTE_REPLAY_CONFLICT",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            IdempotencyConflictException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        "IDEMPOTENCY_CONFLICT",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(InvalidTripStateTransitionException.class)
    public ResponseEntity<ApiError> handleInvalidStateTransition(
            InvalidTripStateTransitionException ex,
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

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLockingFailure(
            org.springframework.dao.OptimisticLockingFailureException ex,
            HttpServletRequest request) {

        log.warn("Optimistic locking failure at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        "CONCURRENT_MODIFICATION_CONFLICT",
                        "The trip was concurrently modified by another transaction. Please refresh and try again.",
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(QuoteNotFoundException.class)
    public ResponseEntity<ApiError> handleQuoteNotFound(
            QuoteNotFoundException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        HttpStatus.NOT_FOUND.value(),
                        "QUOTE_NOT_FOUND",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(TripNotFoundException.class)
    public ResponseEntity<ApiError> handleTripNotFound(
            TripNotFoundException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        HttpStatus.NOT_FOUND.value(),
                        "TRIP_NOT_FOUND",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(QuoteAccessDeniedException.class)
    public ResponseEntity<ApiError> handleQuoteAccessDenied(
            QuoteAccessDeniedException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        HttpStatus.FORBIDDEN.value(),
                        "QUOTE_ACCESS_DENIED",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(TripAccessDeniedException.class)
    public ResponseEntity<ApiError> handleTripAccessDenied(
            TripAccessDeniedException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        HttpStatus.FORBIDDEN.value(),
                        "TRIP_ACCESS_DENIED",
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

    @ExceptionHandler(QuoteExpiredException.class)
    public ResponseEntity<ApiError> handleQuoteExpired(
            QuoteExpiredException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiError.of(
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        "QUOTE_EXPIRED",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(InvalidQuoteException.class)
    public ResponseEntity<ApiError> handleInvalidQuote(
            InvalidQuoteException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "INVALID_QUOTE",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(PricingServiceUnavailableException.class)
    public ResponseEntity<ApiError> handlePricingUnavailable(
            PricingServiceUnavailableException ex,
            HttpServletRequest request) {

        log.warn("Pricing Service unavailable during booking: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "PRICING_SERVICE_UNAVAILABLE",
                        ex.getMessage(),
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

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex,
            HttpServletRequest request) {

        String msg = ex.getMessage();
        if (msg != null && msg.contains("uk_trips_driver_single_active")) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiError.of(
                            HttpStatus.CONFLICT.value(),
                            "DRIVER_ALREADY_HAS_ACTIVE_TRIP",
                            "Driver already has an active trip in progress",
                            request.getRequestURI()
                    ));
        }

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT.value(),
                        "DATA_INTEGRITY_VIOLATION",
                        "Database constraint violation occurred",
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
