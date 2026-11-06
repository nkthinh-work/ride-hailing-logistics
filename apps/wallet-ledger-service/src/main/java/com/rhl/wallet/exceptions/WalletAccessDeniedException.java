package com.rhl.wallet.exceptions;

public class WalletAccessDeniedException extends RuntimeException {
    public WalletAccessDeniedException(String message) {
        super(message);
    }
}
