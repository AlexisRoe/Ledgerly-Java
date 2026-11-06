package com.ledgerly.expenses.internal.domain;

/** A single field value that breaks a rule (blank merchant, future date, too long, ...). */
public final class InvalidInput extends ExpenseException {

    private final String field;

    public InvalidInput(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
