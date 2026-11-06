package com.ledgerly.expenses;

import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.util.List;
import java.util.Optional;

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

    /**
     * Checks an expense against the expense rules without creating it. Empty means valid. Lets batch and import
     * report one bad row without writing the good ones first.
     */
    Optional<ValidationProblem> validate(NewExpense expense);

    /**
     * Creates draft expenses with {@code claimant} as claimant, all in one transaction: either every one is
     * created or none is. This is the only way other modules may create expenses (decision D4).
     *
     * @throws com.ledgerly.shared.error.NotFound unless the claimant is a teammate
     * @throws com.ledgerly.shared.error.InvalidRequest when the list is empty or longer than the batch limit
     */
    List<ExpenseId> createDrafts(TeamId teamId, UserId claimant, List<NewExpense> expenses);
}
