package com.ledgerly.expenses.internal.infrastructure;

import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.Repository;

/**
 * Extends {@link Repository}, not JpaRepository, on purpose: with no inherited {@code findById}, a query that
 * is not scoped to a team cannot be written.
 */
public interface ExpenseRepository extends Repository<Expense, ExpenseId> {

    /** The expense, only if it belongs to that team; otherwise empty (same as not existing). */
    Optional<Expense> findByTeamIdAndId(TeamId teamId, ExpenseId id);

    /** Inserts or updates; a stale version fails with an optimistic-locking exception. */
    Expense save(Expense expense);

    /** Removes the expense and its rejection history. */
    void delete(Expense expense);

    /** Always combine with {@link ExpenseSpecifications#inTeam}; the use case does. */
    Page<Expense> findAll(Specification<Expense> spec, Pageable pageable);
}
