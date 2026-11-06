package com.rhl.wallet.exceptions;

public class InsufficientLedgerBalanceException extends RuntimeException {
    public InsufficientLedgerBalanceException(String message) {
        super(message);
    }
}
