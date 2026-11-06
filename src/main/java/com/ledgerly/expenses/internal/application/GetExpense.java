package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetExpense {

    private final ExpenseRepository expenses;

    GetExpense(ExpenseRepository expenses) {
        this.expenses = expenses;
    }

    @Transactional(readOnly = true)
    public Optional<ExpenseView> execute(TeamId teamId, ExpenseId id) {
        return expenses.findByTeamIdAndId(teamId, id).map(ExpenseView::of);
    }
}
