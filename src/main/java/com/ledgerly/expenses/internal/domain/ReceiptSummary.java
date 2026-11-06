package com.ledgerly.expenses.internal.domain;

/** Receipt counts for one expense, supplied by the receipts module. */
public record ReceiptSummary(int accepted, int pendingScan) {

    public ReceiptSummary {
        if (accepted < 0 || pendingScan < 0) {
            throw new IllegalArgumentException("Counts must not be negative");
        }
    }
}
