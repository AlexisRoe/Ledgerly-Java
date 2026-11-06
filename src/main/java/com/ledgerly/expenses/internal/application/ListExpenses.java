package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.domain.ExpenseStatus;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
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
        Currency currency = ExpenseFilters.currencyOf(q.currency());
        Sort sort = parseSort(q.sort(), currency != null);
        Specification<Expense> spec = ExpenseFilters.build(
                actor,
                q.teamId(),
                new ExpenseFilters.Filter(
                        q.status(), q.category(), q.claimantId(), q.currency(), q.incurredFrom(), q.incurredTo()));

        var page = expenses.findAll(spec, PageRequest.of(q.page(), q.size(), sort));
        return new PageResult(
                page.getContent().stream().map(ExpenseView::of).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
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
