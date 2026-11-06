package com.ledgerly.expenses.internal.domain;

public final class SelfDealing extends ExpenseException {

    public SelfDealing(Action action) {
        super("Claimants cannot " + action + " their own expense");
    }
}
