package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.shared.error.PreconditionFailed;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import java.time.Clock;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UpdateExpense {

    /** Null fields stay unchanged; a blank note clears the note. */
    public record Command(
            TeamId teamId,
            ExpenseId expenseId,
            UserId caller,
            long expectedVersion,
            @Nullable Money money,
            @Nullable Category category,
            @Nullable LocalDate incurredOn,
            @Nullable String merchant,
            @Nullable String note) {}

    private final ExpenseAccess access;
    private final Clock clock;

    UpdateExpense(ExpenseAccess access, Clock clock) {
        this.access = access;
        this.clock = clock;
    }

    @Transactional
    public ExpenseView execute(Command c) {
        Actor actor = access.actor(c.teamId(), c.caller());
        Expense e = access.load(actor, c.teamId(), c.expenseId());
        if (e.version() != c.expectedVersion()) {
            throw new PreconditionFailed();
        }
        String note = c.note() == null ? e.note() : (c.note().isBlank() ? null : c.note());
        e.edit(
                actor,
                c.money() == null ? e.money() : c.money(),
                c.category() == null ? e.category() : c.category(),
                c.incurredOn() == null ? e.incurredOn() : c.incurredOn(),
                c.merchant() == null ? e.merchant() : c.merchant(),
                note,
                clock);
        return access.flushed(e);
    }
}
