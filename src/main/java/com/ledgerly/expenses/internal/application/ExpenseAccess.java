package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/** The two checks every use case starts with: are you a teammate, and may you see this expense. */
@Component
class ExpenseAccess {

    private final MembershipLookup memberships;
    private final ExpenseRepository expenses;
    private final EntityManager entityManager;

    ExpenseAccess(MembershipLookup memberships, ExpenseRepository expenses, EntityManager entityManager) {
        this.memberships = memberships;
        this.expenses = expenses;
        this.entityManager = entityManager;
    }

    /** Writes pending changes now, so the view carries the new version for the ETag. */
    ExpenseView flushed(Expense expense) {
        entityManager.flush();
        return ExpenseView.of(expense);
    }

    /** Not a teammate: 404, so the existence of the team is not revealed. */
    Actor actor(TeamId teamId, UserId userId) {
        return new Actor(userId, memberships.roleOf(teamId, userId).orElseThrow(NotFound::new));
    }

    /** Missing, other team's, or not visible to this actor: all 404. */
    Expense load(Actor actor, TeamId teamId, ExpenseId id) {
        return expenses.findByTeamIdAndId(teamId, id)
                .filter(e -> e.isVisibleTo(actor))
                .orElseThrow(NotFound::new);
    }
}
