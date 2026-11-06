package com.rhl.wallet.exceptions;

public class DuplicateFinancialEventException extends RuntimeException {
    public DuplicateFinancialEventException(String message) {
        super(message);
    }
}
