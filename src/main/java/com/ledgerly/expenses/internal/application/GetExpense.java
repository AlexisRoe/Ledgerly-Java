package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetExpense {

    private final ExpenseRepository expenses;
    private final ExpenseAccess access;

    GetExpense(ExpenseRepository expenses, ExpenseAccess access) {
        this.expenses = expenses;
        this.access = access;
    }

    /** Raw lookup, scoped to the team only. Used by tests; real callers use the caller-aware overload. */
    @Transactional(readOnly = true)
    public Optional<ExpenseView> execute(TeamId teamId, ExpenseId id) {
        return expenses.findByTeamIdAndId(teamId, id).map(ExpenseView::of);
    }

    /** @throws com.ledgerly.shared.error.NotFound unless the caller is a teammate who may see the expense */
    @Transactional(readOnly = true)
    public ExpenseView execute(TeamId teamId, ExpenseId id, UserId caller) {
        Actor actor = access.actor(teamId, caller);
        return ExpenseView.of(access.load(actor, teamId, id));
    }
}
