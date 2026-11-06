package com.ledgerly.expenses.internal.infrastructure;

import com.ledgerly.expenses.internal.domain.Category;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.domain.ExpenseStatus;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.time.LocalDate;
import java.util.Currency;
import org.springframework.data.jpa.domain.Specification;

/** Building blocks for the list query. Every query starts with {@link #inTeam}. */
public final class ExpenseSpecifications {

    private ExpenseSpecifications() {}

    public static Specification<Expense> inTeam(TeamId teamId) {
        return (root, query, cb) -> cb.equal(root.get("teamId"), teamId);
    }

    public static Specification<Expense> hasStatus(ExpenseStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Expense> hasCategory(Category category) {
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    public static Specification<Expense> claimedBy(UserId claimant) {
        return (root, query, cb) -> cb.equal(root.get("claimantId"), claimant);
    }

    public static Specification<Expense> inCurrency(Currency currency) {
        return (root, query, cb) -> cb.equal(root.get("money").get("currency"), currency);
    }

    public static Specification<Expense> incurredFrom(LocalDate from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("incurredOn"), from);
    }

    public static Specification<Expense> incurredTo(LocalDate to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("incurredOn"), to);
    }
}
