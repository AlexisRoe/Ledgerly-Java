package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.ReceiptChecks;
import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.expenses.internal.domain.Action;
import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The six workflow transitions. */
@Service
public class TransitionExpense {

    private final ExpenseAccess access;
    private final ReceiptChecks receipts;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final MeterRegistry metrics;

    TransitionExpense(
            ExpenseAccess access,
            ReceiptChecks receipts,
            ApplicationEventPublisher events,
            Clock clock,
            MeterRegistry metrics) {
        this.access = access;
        this.receipts = receipts;
        this.events = events;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** @param reason only used by {@link Action#REJECT} */
    @Transactional
    public ExpenseView execute(Action action, TeamId teamId, ExpenseId id, UserId caller, @Nullable String reason) {
        Actor actor = access.actor(teamId, caller);
        Expense e = access.load(actor, teamId, id);
        switch (action) {
            case SUBMIT -> e.submit(actor, receipts.summaryFor(e.id()));
            case WITHDRAW -> e.withdraw(actor);
            case APPROVE -> e.approve(actor);
            case REJECT -> e.reject(actor, reason);
            case REOPEN -> e.reopen(actor);
            case REIMBURSE -> e.reimburse(actor);
        }
        Instant now = clock.instant();
        // Published in the same transaction as the change: either both happen or neither does.
        events.publishEvent(
                switch (action) {
                    case SUBMIT -> new ExpenseEvent.Submitted(teamId, id, caller, now);
                    case WITHDRAW -> new ExpenseEvent.Withdrawn(teamId, id, caller, now);
                    case APPROVE -> new ExpenseEvent.Approved(teamId, id, caller, now);
                    case REJECT -> new ExpenseEvent.Rejected(teamId, id, caller, now);
                    case REOPEN -> new ExpenseEvent.Reopened(teamId, id, caller, now);
                    case REIMBURSE -> new ExpenseEvent.Reimbursed(teamId, id, caller, now);
                });
        // Counted per action. Only transitions that succeeded get here: a refusal throws above.
        metrics.counter("ledgerly.expenses.transitions", "action", action.name().toLowerCase(Locale.ROOT))
                .increment();
        return access.flushed(e);
    }
}
