package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExpensesFacadeImpl implements ExpensesFacade {

    private final ExpenseAccess access;

    ExpensesFacadeImpl(ExpenseAccess access) {
        this.access = access;
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
