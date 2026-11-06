package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.internal.domain.Actor;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.domain.ExpenseStatus;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything the CSV export needs, handed out page by page: no more than {@code maxExportRows} rows, never the
 * whole result in memory, and each page read in its own short transaction so a slow download holds no connection.
 */
@Service
public class ExportExpenses {

    static final int PAGE = 500;

    /** Who asks (for the team and visibility rules) and the same optional filters as the list. */
    public record Query(
            TeamId teamId,
            UserId caller,
            @Nullable ExpenseStatus status,
            @Nullable Category category,
            @Nullable UserId claimantId,
            @Nullable String currency,
            @Nullable LocalDate incurredFrom,
            @Nullable LocalDate incurredTo) {}

    /**
     * @param total how many expenses match, which may be more than will be sent
     * @param truncated true when {@code total} exceeds the cap
     * @param nextPage returns the next batch of rows, and an empty list when everything has been sent
     */
    public record Export(long total, boolean truncated, Supplier<List<ExpenseView>> nextPage) {}

    private record FirstPage(long total, List<ExpenseView> rows) {}

    private final ExpenseAccess access;
    private final ExpenseRepository expenses;
    private final LedgerlyLimits limits;
    private final TransactionTemplate readOnly;

    ExportExpenses(
            ExpenseAccess access,
            ExpenseRepository expenses,
            LedgerlyLimits limits,
            PlatformTransactionManager transactionManager) {
        this.access = access;
        this.expenses = expenses;
        this.limits = limits;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * Returns the total plus a supplier that yields the rows page by page, so a large export is never held in memory.
     */
    public Export execute(Query q) {
        Actor actor = readOnly.execute(s -> access.actor(q.teamId(), q.caller()));
        Specification<Expense> spec = ExpenseFilters.build(
                actor,
                q.teamId(),
                new ExpenseFilters.Filter(
                        q.status(), q.category(), q.claimantId(), q.currency(), q.incurredFrom(), q.incurredTo()));
        // Newest first; the id breaks ties so paging through the export never repeats or skips a row.
        Sort sort = Sort.by(Sort.Direction.DESC, "incurredOn").and(Sort.by(Sort.Direction.DESC, "id.value"));
        int cap = limits.maxExportRows();

        // Rows are turned into views inside the transaction: the entity's collections load lazily.
        var first = readOnly.execute(s -> {
            var page = expenses.findAll(spec, PageRequest.of(0, PAGE, sort));
            return new FirstPage(
                    page.getTotalElements(),
                    page.getContent().stream().map(ExpenseView::of).toList());
        });
        long total = first.total();
        int[] sent = {0};
        int[] pageNumber = {0};
        var pending = new java.util.ArrayDeque<List<ExpenseView>>();
        pending.add(first.rows());

        Supplier<List<ExpenseView>> next = () -> {
            List<ExpenseView> rows = pending.poll();
            if (rows == null) {
                if (sent[0] >= cap || (long) pageNumber[0] * PAGE + PAGE >= total) {
                    return List.of();
                }
                pageNumber[0]++;
                rows = readOnly.execute(s ->
                        expenses.findAll(spec, PageRequest.of(pageNumber[0], PAGE, sort)).getContent().stream()
                                .map(ExpenseView::of)
                                .toList());
            }
            int room = cap - sent[0];
            if (rows.size() > room) {
                rows = rows.subList(0, Math.max(room, 0));
            }
            sent[0] += rows.size();
            return rows;
        };
        return new Export(total, total > cap, next);
    }
}
