package com.ledgerly.receipts.internal.application;

import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.receipts.internal.infrastructure.ReceiptRepository;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListReceipts {

    private final ExpensesFacade expenses;
    private final ReceiptRepository receipts;

    ListReceipts(ExpensesFacade expenses, ReceiptRepository receipts) {
        this.expenses = expenses;
        this.receipts = receipts;
    }

    /** Visible to whoever can see the expense itself. */
    @Transactional(readOnly = true)
    public List<ReceiptView> execute(TeamId teamId, ExpenseId expenseId, UserId caller) {
        expenses.requireCanView(teamId, expenseId, caller);
        return receipts.findByTeamIdAndExpenseIdOrderByCreatedAt(teamId.value(), expenseId.value()).stream()
                .map(ReceiptView::of)
                .toList();
    }
}
