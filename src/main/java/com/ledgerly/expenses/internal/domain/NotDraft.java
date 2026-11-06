package com.ledgerly.expenses.internal.domain;

/** An operation that only a draft allows (edit, delete, receipts) was attempted on another status. */
public final class NotDraft extends ExpenseException {

    public NotDraft(ExpenseStatus status) {
        super("Only a draft allows this, but the expense is " + status);
    }
}
