package com.ledgerly.expenses;

import com.ledgerly.shared.id.ExpenseId;

/**
 * What expenses needs to know about receipts when submitting. Expenses owns this interface and receipts
 * implements it (M7), so both module arrows point from receipts to expenses (ADR 0002).
 */
public interface ReceiptChecks {

    ReceiptSummary summaryFor(ExpenseId expenseId);
}
