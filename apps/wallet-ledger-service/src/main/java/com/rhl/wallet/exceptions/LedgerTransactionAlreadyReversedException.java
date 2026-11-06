package com.rhl.wallet.exceptions;

import java.util.UUID;

public class LedgerTransactionAlreadyReversedException extends RuntimeException {
    public LedgerTransactionAlreadyReversedException(UUID transactionId) {
        super("Ledger transaction " + transactionId + " is already reversed");
    }
}
