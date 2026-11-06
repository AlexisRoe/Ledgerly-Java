package com.ledgerly.shared.money;

import java.util.Currency;

/** Thrown when two {@link Money} values of different currencies are combined or compared. */
public final class CurrencyMismatch extends RuntimeException {

    /** Thrown when two amounts in different currencies are added or compared. */
    public CurrencyMismatch(Currency left, Currency right) {
        super("Currency mismatch: " + left + " vs " + right);
    }
}
