package com.ledgerly.expenses.internal.application;

import static com.ledgerly.expenses.internal.infrastructure.ExpenseSpecifications.*;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.domain.ExpenseStatus;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import java.time.LocalDate;
import java.util.Currency;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/** The filters of the expense list, shared by the paged list and the CSV export so they can never disagree. */
final class ExpenseFilters {

    /** Optional narrowing criteria; null means "do not filter on this". */
    record Filter(
            @Nullable ExpenseStatus status,
            @Nullable Category category,
            @Nullable UserId claimantId,
            @Nullable String currency,
            @Nullable LocalDate incurredFrom,
            @Nullable LocalDate incurredTo) {}

    private ExpenseFilters() {}

    static @Nullable Currency currencyOf(@Nullable String code) {
        if (code == null) {
            return null;
        }
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequest("currency", "Unknown currency code");
        }
    }

    /** Always scoped to the team; a MEMBER is forced to their own expenses whatever claimant they ask for. */
    static Specification<Expense> build(Actor actor, TeamId teamId, Filter f) {
        if (f.incurredFrom() != null
                && f.incurredTo() != null
                && f.incurredFrom().isAfter(f.incurredTo())) {
            throw new InvalidRequest("incurredFrom", "incurredFrom must not be after incurredTo");
        }
        Currency currency = currencyOf(f.currency());
        Specification<Expense> spec = inTeam(teamId);
        UserId claimant = actor.role().includes(Role.APPROVER) ? f.claimantId() : actor.id();
        if (claimant != null) spec = spec.and(claimedBy(claimant));
        if (f.status() != null) spec = spec.and(hasStatus(f.status()));
        if (f.category() != null) spec = spec.and(hasCategory(f.category()));
        if (currency != null) spec = spec.and(inCurrency(currency));
        if (f.incurredFrom() != null) spec = spec.and(incurredFrom(f.incurredFrom()));
        if (f.incurredTo() != null) spec = spec.and(incurredTo(f.incurredTo()));
        return spec;
    }
}
