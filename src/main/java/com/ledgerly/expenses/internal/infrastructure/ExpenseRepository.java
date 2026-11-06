package com.ledgerly.expenses.internal.infrastructure;

import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Extends {@link Repository}, not JpaRepository, on purpose: with no inherited {@code findById}, a query that
 * is not scoped to a team cannot be written.
 */
public interface ExpenseRepository extends Repository<Expense, ExpenseId> {

    Optional<Expense> findByTeamIdAndId(TeamId teamId, ExpenseId id);

    Expense save(Expense expense);

    void delete(Expense expense);
}
