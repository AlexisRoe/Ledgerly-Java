package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.internal.domain.Category;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import java.time.Clock;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateExpense {

    public record Command(
            TeamId teamId,
            UserId claimantId,
            Money money,
            Category category,
            LocalDate incurredOn,
            String merchant,
            @Nullable String note) {}

    private final ExpenseRepository expenses;
    private final Clock clock;

    CreateExpense(ExpenseRepository expenses, Clock clock) {
        this.expenses = expenses;
        this.clock = clock;
    }

    @Transactional
    public ExpenseView execute(Command c) {
        Expense expense = Expense.draft(
                c.teamId(), c.claimantId(), c.money(), c.category(), c.incurredOn(), c.merchant(), c.note(), clock);
        return ExpenseView.of(expenses.save(expense));
    }
}
