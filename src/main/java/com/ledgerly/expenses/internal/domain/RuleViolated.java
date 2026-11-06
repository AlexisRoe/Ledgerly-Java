package com.ledgerly.expenses.internal.domain;

/** Input or state breaks a business rule (future date, missing receipt, blank reason, ...). */
public final class RuleViolated extends ExpenseException {

    public RuleViolated(String message) {
        super(message);
    }
}
