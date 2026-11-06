package com.rhl.wallet.exceptions;

public class InvalidLedgerTransactionException extends RuntimeException {
    public InvalidLedgerTransactionException(String message) {
        super(message);
    }
}
