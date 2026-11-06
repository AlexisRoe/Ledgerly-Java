package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.error.PreconditionFailed;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeleteExpense {

    private final ExpenseAccess access;
    private final ExpenseRepository expenses;

    DeleteExpense(ExpenseAccess access, ExpenseRepository expenses) {
        this.access = access;
        this.expenses = expenses;
    }

    @Transactional
    public void execute(TeamId teamId, ExpenseId id, UserId caller, long expectedVersion) {
        Actor actor = access.actor(teamId, caller);
        Expense e = access.load(actor, teamId, id);
        if (e.version() != expectedVersion) {
            throw new PreconditionFailed();
        }
        e.requireDeletableBy(actor);
        expenses.delete(e);
    }
}
