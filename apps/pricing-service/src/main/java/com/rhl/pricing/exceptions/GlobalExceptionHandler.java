package com.rhl.pricing.exceptions;

import com.rhl.pricing.dtos.ApiError;
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

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        403,
                        "ACCESS_DENIED",
                        "You do not have permission to perform this pricing administration action",
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
                        403,
                        "ACCESS_DENIED",
                        ex.getMessage(),
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
                        404,
                        "QUOTE_NOT_FOUND",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(QuoteExpiredException.class)
    public ResponseEntity<ApiError> handleQuoteExpired(
            QuoteExpiredException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.GONE)
                .body(ApiError.of(
                        410,
                        "QUOTE_EXPIRED",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(ProductionQuotingBlockedException.class)
    public ResponseEntity<ApiError> handleProductionQuotingBlocked(
            ProductionQuotingBlockedException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiError.of(
                        422,
                        "PRODUCTION_QUOTING_BLOCKED",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(PricingRuleConflictException.class)
    public ResponseEntity<ApiError> handlePricingRuleConflict(
            PricingRuleConflictException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        409,
                        "PRICING_RULE_CONFLICT",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(PricingRuleNotFoundException.class)
    public ResponseEntity<ApiError> handlePricingRuleNotFound(
            PricingRuleNotFoundException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        404,
                        "PRICING_RULE_NOT_FOUND",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(PricingRuleImmutableException.class)
    public ResponseEntity<ApiError> handlePricingRuleImmutable(
            PricingRuleImmutableException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiError.of(
                        422,
                        "PRICING_RULE_IMMUTABLE",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(InvalidPricingRuleException.class)
    public ResponseEntity<ApiError> handleInvalidPricingRule(
            InvalidPricingRuleException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .badRequest()
                .body(ApiError.of(
                        400,
                        "INVALID_PRICING_RULE",
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
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        return ResponseEntity
                .badRequest()
                .body(ApiError.of(
                        400,
                        "INVALID_ARGUMENT",
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
                        500,
                        "INTERNAL_ERROR",
                        "An unexpected error occurred",
                        request.getRequestURI()
                ));
    }
}
