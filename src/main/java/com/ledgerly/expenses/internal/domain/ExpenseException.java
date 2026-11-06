package com.ledgerly.expenses.internal.domain;

/** Base of everything the Expense domain refuses to do. */
public abstract sealed class ExpenseException extends RuntimeException
        permits InvalidTransition, SelfDealing, NotAllowed, RuleViolated {

    protected ExpenseException(String message) {
        super(message);
    }
}
