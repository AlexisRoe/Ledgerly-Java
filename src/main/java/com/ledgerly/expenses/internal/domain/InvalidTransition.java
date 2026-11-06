package com.ledgerly.expenses.internal.domain;

/** The action is not possible from the expense's current status (for example approving a draft). */
public final class InvalidTransition extends ExpenseException {

    /**
     * @param from the current status
     * @param action what was attempted
     */
    public InvalidTransition(ExpenseStatus from, Action action) {
        super("Expense is " + from + " and cannot " + action);
    }
}
