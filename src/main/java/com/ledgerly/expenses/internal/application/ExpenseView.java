package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.domain.ExpenseStatus;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** What leaves the application layer: never the entity itself. */
public record ExpenseView(
        ExpenseId id,
        TeamId teamId,
        UserId claimantId,
        Money money,
        Category category,
        LocalDate incurredOn,
        String merchant,
        @Nullable String note,
        ExpenseStatus status,
        List<String> rejectionReasons,
        long version) {

    /** Copies an entity into the immutable shape handed to the web layer; rejection reasons are flattened to text. */
    static ExpenseView of(Expense e) {
        return new ExpenseView(
                e.id(),
                e.teamId(),
                e.claimantId(),
                e.money(),
                e.category(),
                e.incurredOn(),
                e.merchant(),
                e.note(),
                e.status(),
                e.rejections().stream().map(r -> r.reason()).toList(),
                e.version());
    }
}
