package com.ledgerly.expenses;

import com.ledgerly.shared.id.ExpenseId;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test double for the receipts module (real implementation arrives in M7). */
@Component
@Profile("test")
public class FakeReceiptChecks implements ReceiptChecks {

    public volatile ReceiptSummary next = new ReceiptSummary(1, 0);

    @Override
    public ReceiptSummary summaryFor(ExpenseId expenseId) {
        return next;
    }
}
