package com.ledgerly.expenses;

import com.ledgerly.shared.money.Money;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A draft expense to be created for a claimant: what batch and import hand to
 * {@link ExpensesFacade#createDrafts}. Its fields are checked by the expense rules, not here.
 */
public record NewExpense(
        Money money,
        Category category,
        LocalDate incurredOn,
        String merchant,
        @Nullable String note) {}
