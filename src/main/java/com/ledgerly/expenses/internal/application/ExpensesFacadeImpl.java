package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.expenses.NewExpense;
import com.ledgerly.expenses.ValidationProblem;
import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.domain.InvalidInput;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExpensesFacadeImpl implements ExpensesFacade {

    private final ExpenseAccess access;
    private final ExpenseRepository expenses;
    private final LedgerlyLimits limits;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ExpensesFacadeImpl(
            ExpenseAccess access,
            ExpenseRepository expenses,
            LedgerlyLimits limits,
            ApplicationEventPublisher events,
            Clock clock) {
        this.access = access;
        this.expenses = expenses;
        this.limits = limits;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Optional<ValidationProblem> validate(NewExpense expense) {
        try {
            Expense.checkFields(expense.incurredOn(), expense.merchant(), expense.note(), clock);
            return Optional.empty();
        } catch (InvalidInput e) {
            return Optional.of(new ValidationProblem(e.field(), String.valueOf(e.getMessage())));
        }
    }

    @Override
    @Transactional
    public List<ExpenseId> createDrafts(TeamId teamId, UserId claimant, List<NewExpense> drafts) {
        if (drafts.isEmpty() || drafts.size() > limits.maxBatchSize()) {
            throw new InvalidRequest("items", "Between 1 and " + limits.maxBatchSize() + " expenses at a time");
        }
        access.actor(teamId, claimant); // not a teammate: 404
        List<Expense> created = drafts.stream()
                .map(d -> Expense.draft(
                        teamId, claimant, d.money(), d.category(), d.incurredOn(), d.merchant(), d.note(), clock))
                .toList();
        List<ExpenseId> ids = created.stream().map(e -> expenses.save(e).id()).toList();
        Instant now = clock.instant();
        ids.forEach(id -> events.publishEvent(new ExpenseEvent.Created(teamId, id, claimant, now)));
        return ids;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireCanView(TeamId teamId, ExpenseId expenseId, UserId caller) {
        access.load(access.actor(teamId, caller), teamId, expenseId);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireCanChangeReceipts(TeamId teamId, ExpenseId expenseId, UserId caller) {
        var actor = access.actor(teamId, caller);
        access.load(actor, teamId, expenseId).requireCanChangeReceiptsBy(actor);
    }
}
