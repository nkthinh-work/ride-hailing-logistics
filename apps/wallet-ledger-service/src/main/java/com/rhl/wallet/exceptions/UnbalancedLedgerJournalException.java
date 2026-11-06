package com.rhl.wallet.exceptions;

import java.math.BigDecimal;

public class UnbalancedLedgerJournalException extends RuntimeException {

    private final BigDecimal totalDebit;
    private final BigDecimal totalCredit;
    private final String currency;

    public UnbalancedLedgerJournalException(BigDecimal totalDebit, BigDecimal totalCredit, String currency) {
        super("Unbalanced ledger journal: total DEBIT (" + totalDebit + " " + currency + ") does not equal total CREDIT (" + totalCredit + " " + currency + ")");
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
        this.currency = currency;
    }

    public BigDecimal getTotalDebit() {
        return totalDebit;
    }

    public BigDecimal getTotalCredit() {
        return totalCredit;
    }

    public String getCurrency() {
        return currency;
    }
}
