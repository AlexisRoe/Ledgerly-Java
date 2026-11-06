package com.ledgerly.expenses.internal.application;

import static com.ledgerly.expenses.internal.infrastructure.ExpenseSpecifications.*;

import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Category;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.domain.ExpenseStatus;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListExpenses {

    /** The only fields a client may sort by. Anything else is rejected, never passed to the query. */
    public enum SortField {
        INCURRED_ON("incurredOn", "incurredOn"),
        CREATED_AT("createdAt", "createdAt"),
        AMOUNT("amount", "money.amount");

        private final String apiName;
        private final String property;

        SortField(String apiName, String property) {
            this.apiName = apiName;
            this.property = property;
        }

        static SortField fromApi(String name) {
            for (SortField f : values()) {
                if (f.apiName.equals(name)) {
                    return f;
                }
            }
            throw new InvalidRequest("sort", "Sort field must be one of incurredOn, createdAt, amount");
        }
    }

    public record Query(
            TeamId teamId,
            UserId caller,
            int page,
            int size,
            String sort,
            @Nullable ExpenseStatus status,
            @Nullable Category category,
            @Nullable UserId claimantId,
            @Nullable String currency,
            @Nullable LocalDate incurredFrom,
            @Nullable LocalDate incurredTo) {}

    public record PageResult(List<ExpenseView> items, int page, int size, long totalElements, int totalPages) {}

    private final ExpenseAccess access;
    private final ExpenseRepository expenses;
    private final LedgerlyLimits limits;

    ListExpenses(ExpenseAccess access, ExpenseRepository expenses, LedgerlyLimits limits) {
        this.access = access;
        this.expenses = expenses;
        this.limits = limits;
    }

    @Transactional(readOnly = true)
    public PageResult execute(Query q) {
        Actor actor = access.actor(q.teamId(), q.caller());
        if (q.page() < 0) {
            throw new InvalidRequest("page", "page must not be negative");
        }
        if (q.size() < 1 || q.size() > limits.maxPageSize()) {
            throw new InvalidRequest("size", "size must be between 1 and " + limits.maxPageSize());
        }
        if (q.incurredFrom() != null
                && q.incurredTo() != null
                && q.incurredFrom().isAfter(q.incurredTo())) {
            throw new InvalidRequest("incurredFrom", "incurredFrom must not be after incurredTo");
        }
        Currency currency = parseCurrency(q.currency());
        Sort sort = parseSort(q.sort(), currency != null);

        Specification<Expense> spec = inTeam(q.teamId());
        // A MEMBER only ever sees their own expenses, whatever filter they send.
        UserId claimant = actor.role().includes(Role.APPROVER) ? q.claimantId() : actor.id();
        if (claimant != null) spec = spec.and(claimedBy(claimant));
        if (q.status() != null) spec = spec.and(hasStatus(q.status()));
        if (q.category() != null) spec = spec.and(hasCategory(q.category()));
        if (currency != null) spec = spec.and(inCurrency(currency));
        if (q.incurredFrom() != null) spec = spec.and(incurredFrom(q.incurredFrom()));
        if (q.incurredTo() != null) spec = spec.and(incurredTo(q.incurredTo()));

        var page = expenses.findAll(spec, PageRequest.of(q.page(), q.size(), sort));
        return new PageResult(
                page.getContent().stream().map(ExpenseView::of).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    private static @Nullable Currency parseCurrency(@Nullable String code) {
        if (code == null) {
            return null;
        }
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequest("currency", "Unknown currency code");
        }
    }

    private static Sort parseSort(String raw, boolean hasCurrencyFilter) {
        String[] parts = raw.split(",", -1);
        if (parts.length > 2) {
            throw new InvalidRequest("sort", "Use <field>,<asc|desc>");
        }
        SortField field = SortField.fromApi(parts[0]);
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            direction = switch (parts[1]) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw new InvalidRequest("sort", "Direction must be asc or desc");
            };
        }
        if (field == SortField.AMOUNT && !hasCurrencyFilter) {
            throw new InvalidRequest("sort", "Sorting by amount needs a currency filter");
        }
        // The id is a unique tie-breaker, so pages never overlap or skip rows.
        return Sort.by(direction, field.property).and(Sort.by(direction, "id.value"));
    }
}
