package com.ledgerly.expenses.internal.domain;

/** The actor's role, or their relation to the expense, does not permit the operation. */
public final class NotAllowed extends ExpenseException {

    public NotAllowed(String message) {
        super(message);
    }
}
