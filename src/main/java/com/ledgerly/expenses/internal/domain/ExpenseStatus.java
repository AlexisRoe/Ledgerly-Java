package com.ledgerly.expenses.internal.domain;

/** Lifecycle: DRAFT -> SUBMITTED -> APPROVED -> REIMBURSED, or SUBMITTED -> REJECTED -> DRAFT (reopen). */
public enum ExpenseStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    REIMBURSED
}
