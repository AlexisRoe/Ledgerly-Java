package com.ledgerly.receipts.internal.application;

import com.ledgerly.expenses.ReceiptChecks;
import com.ledgerly.expenses.ReceiptSummary;
import com.ledgerly.receipts.internal.domain.ScanStatus;
import com.ledgerly.receipts.internal.infrastructure.ReceiptRepository;
import com.ledgerly.shared.id.ExpenseId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Receipts' side of decision D3: expenses defines the interface, receipts implements it. */
@Component
class ReceiptChecksImpl implements ReceiptChecks {

    private final ReceiptRepository receipts;

    ReceiptChecksImpl(ReceiptRepository receipts) {
        this.receipts = receipts;
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptSummary summaryFor(ExpenseId expenseId) {
        return new ReceiptSummary(
                (int) receipts.countByExpenseIdAndScanStatus(expenseId.value(), ScanStatus.ACCEPTED),
                (int) receipts.countByExpenseIdAndScanStatus(expenseId.value(), ScanStatus.PENDING_SCAN));
    }
}
