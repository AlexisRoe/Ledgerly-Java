package com.ledgerly.expenses.internal.domain;

public final class InvalidTransition extends ExpenseException {

    public InvalidTransition(ExpenseStatus from, Action action) {
        super("Expense is " + from + " and cannot " + action);
    }
}
