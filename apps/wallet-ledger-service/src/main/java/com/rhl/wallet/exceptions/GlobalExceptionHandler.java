package com.rhl.wallet.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(WalletNotFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(WalletAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleWalletAccessDenied(WalletAccessDeniedException ex) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleSpringAccessDenied(AccessDeniedException ex) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to access this resource");
    }

    @ExceptionHandler(InsufficientLedgerBalanceException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientBalance(InsufficientLedgerBalanceException ex) {
        log.warn("Insufficient balance rejected: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "INSUFFICIENT_LEDGER_BALANCE", ex.getMessage());
    }

    @ExceptionHandler(UnbalancedLedgerJournalException.class)
    public ResponseEntity<Map<String, Object>> handleUnbalancedJournal(UnbalancedLedgerJournalException ex) {
        log.warn("Unbalanced journal rejected: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "UNBALANCED_JOURNAL", ex.getMessage());
    }

    @ExceptionHandler(InvalidLedgerTransactionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTransaction(InvalidLedgerTransactionException ex) {
        log.warn("Invalid ledger transaction: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "INVALID_LEDGER_TRANSACTION", ex.getMessage());
    }

    @ExceptionHandler(DuplicateFinancialEventException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateEvent(DuplicateFinancialEventException ex) {
        return buildErrorResponse(HttpStatus.CONFLICT, "DUPLICATE_EVENT", ex.getMessage());
    }

    @ExceptionHandler(LedgerTransactionAlreadyReversedException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyReversed(LedgerTransactionAlreadyReversedException ex) {
        return buildErrorResponse(HttpStatus.CONFLICT, "TRANSACTION_ALREADY_REVERSED", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getBindingResult().getAllErrors().getFirst().getDefaultMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unhandled exception in wallet-ledger-service", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", error,
                "message", message != null ? message : ""
        ));
    }
}
