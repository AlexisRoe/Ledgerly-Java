package com.ledgerly.expenses;

import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;

/**
 * The expenses module's public API for modules that hang data off an expense (receipts, imports). Both methods
 * throw the usual API errors instead of returning flags, so callers cannot forget to check.
 */
public interface ExpensesFacade {

    /**
     * @throws com.ledgerly.shared.error.NotFound unless the caller is a teammate who may see the expense
     */
    void requireCanView(TeamId teamId, ExpenseId expenseId, UserId caller);

    /**
     * Receipts may be added or removed only by the claimant, only while the expense is a draft.
     *
     * @throws com.ledgerly.shared.error.NotFound unless the caller can see the expense
     */
    void requireCanChangeReceipts(TeamId teamId, ExpenseId expenseId, UserId caller);
}
