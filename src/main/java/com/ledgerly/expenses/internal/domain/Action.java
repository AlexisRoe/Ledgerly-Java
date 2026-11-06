package com.ledgerly.expenses.internal.domain;

/** The six workflow transitions. */
public enum Action {
    SUBMIT,
    WITHDRAW,
    APPROVE,
    REJECT,
    REOPEN,
    REIMBURSE
}
