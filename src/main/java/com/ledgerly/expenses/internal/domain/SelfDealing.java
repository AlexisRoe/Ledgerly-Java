package com.ledgerly.expenses.internal.domain;

/** A claimant tried to decide on their own expense (approve, reject, reimburse). */
public final class SelfDealing extends ExpenseException {

    /** @param action the decision the claimant tried to take on their own expense */
    public SelfDealing(Action action) {
        super("Claimants cannot " + action + " their own expense");
    }
}
