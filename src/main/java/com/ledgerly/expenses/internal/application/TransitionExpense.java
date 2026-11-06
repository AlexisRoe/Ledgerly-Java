package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.ReceiptChecks;
import com.ledgerly.expenses.internal.domain.Action;
import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The six workflow transitions. */
@Service
public class TransitionExpense {

    private final ExpenseAccess access;
    private final ReceiptChecks receipts;

    TransitionExpense(ExpenseAccess access, ReceiptChecks receipts) {
        this.access = access;
        this.receipts = receipts;
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
        return access.flushed(e);
    }
}
