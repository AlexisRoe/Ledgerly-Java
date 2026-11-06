package com.ledgerly.expenses;

import com.ledgerly.shared.id.ExpenseId;

/**
 * What expenses needs to know about receipts when submitting. Expenses owns this interface and receipts
 * implements it (M7), so both module arrows point from receipts to expenses (ADR 0002).
 */
public interface ReceiptChecks {

    /** Counts the receipts attached to the expense, by scan outcome. Submitting needs at least one accepted. */
    ReceiptSummary summaryFor(ExpenseId expenseId);
}
