package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.shared.error.PreconditionFailed;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;
    private final Clock clock;

    UpdateExpense(ExpenseAccess access, ApplicationEventPublisher events, Clock clock) {
        this.access = access;
        this.events = events;
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
        Money money = c.money() == null ? e.money() : c.money();
        Category category = c.category() == null ? e.category() : c.category();
        LocalDate incurredOn = c.incurredOn() == null ? e.incurredOn() : c.incurredOn();
        String merchant = c.merchant() == null ? e.merchant() : c.merchant();

        // Only the names of the fields that really change go into the event, never their values.
        List<String> changed = new ArrayList<>();
        if (!money.equals(e.money())) changed.add("money");
        if (category != e.category()) changed.add("category");
        if (!incurredOn.equals(e.incurredOn())) changed.add("incurredOn");
        if (!merchant.equals(e.merchant())) changed.add("merchant");
        if (!Objects.equals(note, e.note())) changed.add("note");

        e.edit(actor, money, category, incurredOn, merchant, note, clock);
        events.publishEvent(new ExpenseEvent.Edited(c.teamId(), c.expenseId(), c.caller(), changed, clock.instant()));
        return access.flushed(e);
    }
}
