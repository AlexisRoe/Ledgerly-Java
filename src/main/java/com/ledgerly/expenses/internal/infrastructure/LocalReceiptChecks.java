package com.ledgerly.expenses.internal.infrastructure;

import com.ledgerly.expenses.ReceiptChecks;
import com.ledgerly.expenses.ReceiptSummary;
import com.ledgerly.shared.id.ExpenseId;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** TEMPORARY (until M7): pretends every expense has one accepted receipt, so submitting works locally. */
@Component
@Profile("local & !test")
class LocalReceiptChecks implements ReceiptChecks {

    @Override
    public ReceiptSummary summaryFor(ExpenseId expenseId) {
        return new ReceiptSummary(1, 0);
    }
}
