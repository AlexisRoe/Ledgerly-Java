package com.ledgerly.expenses.internal.domain;

/** A single field value that breaks a rule (blank merchant, future date, too long, ...). */
public final class InvalidInput extends ExpenseException {

    private final String field;

    /** @param field the field as the API names it */
    public InvalidInput(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The name of the offending field, as the API calls it. */
    public String field() {
        return field;
    }
}
