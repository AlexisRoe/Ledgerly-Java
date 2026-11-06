package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.error.PreconditionFailed;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deletes a draft expense; only its claimant may, and only with the current version. */
@Service
public class DeleteExpense {

    private final ExpenseAccess access;
    private final ExpenseRepository expenses;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DeleteExpense(ExpenseAccess access, ExpenseRepository expenses, ApplicationEventPublisher events, Clock clock) {
        this.access = access;
        this.expenses = expenses;
        this.events = events;
        this.clock = clock;
    }

    /** @param expectedVersion the version the client saw (from If-Match); a stale one fails with 412 */
    @Transactional
    public void execute(TeamId teamId, ExpenseId id, UserId caller, long expectedVersion) {
        Actor actor = access.actor(teamId, caller);
        Expense e = access.load(actor, teamId, id);
        if (e.version() != expectedVersion) {
            throw new PreconditionFailed();
        }
        e.requireDeletableBy(actor);
        expenses.delete(e);
        events.publishEvent(new ExpenseEvent.Deleted(e.teamId(), e.id(), caller, clock.instant()));
    }
}
