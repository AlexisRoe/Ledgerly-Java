package com.ledgerly.expenses.internal.web;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.NewExpense;
import com.ledgerly.expenses.internal.application.BatchCreateExpenses;
import com.ledgerly.expenses.internal.application.ExpenseView;
import com.ledgerly.expenses.internal.application.ListExpenses;
import com.ledgerly.shared.api.model.BatchExpenseItem;
import com.ledgerly.shared.api.model.BatchItemResult;
import com.ledgerly.shared.api.model.BatchItemStatus;
import com.ledgerly.shared.api.model.BatchResponse;
import com.ledgerly.shared.api.model.ExpenseResponse;
import com.ledgerly.shared.api.model.ItemProblem;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

/** DTO ↔ domain translation. Entities never reach this class; only views do. */
final class ExpenseMapper {

    private ExpenseMapper() {}

    static Money toMoney(com.ledgerly.shared.api.model.Money dto, String field) {
        try {
            return new Money(new BigDecimal(dto.getAmount()), Currency.getInstance(dto.getCurrency()));
        } catch (IllegalArgumentException e) {
            // NumberFormatException is an IllegalArgumentException too.
            throw new InvalidRequest(field, safeMessage(e));
        }
    }

    static Category toDomain(com.ledgerly.shared.api.model.Category dto) {
        return Category.valueOf(dto.name());
    }

    static ExpenseResponse toResponse(ExpenseView view) {
        var money = new com.ledgerly.shared.api.model.Money(
                view.money().amount().toPlainString(), view.money().currency().getCurrencyCode());
        return new ExpenseResponse(
                        view.id().value(),
                        view.teamId().value(),
                        view.claimantId().value(),
                        money,
                        com.ledgerly.shared.api.model.Category.valueOf(
                                view.category().name()),
                        view.incurredOn(),
                        view.merchant(),
                        com.ledgerly.shared.api.model.ExpenseStatus.valueOf(
                                view.status().name()),
                        view.rejectionReasons())
                .note(view.note());
    }

    static com.ledgerly.shared.api.model.ExpensePage toPage(ListExpenses.PageResult result) {
        var meta = new com.ledgerly.shared.api.model.PageMetadata(
                result.size(), result.page(), result.totalElements(), result.totalPages());
        return new com.ledgerly.shared.api.model.ExpensePage(
                result.items().stream().map(ExpenseMapper::toResponse).toList(), meta);
    }

    /** One batch item: parsed as far as possible, with parse failures kept as that item's problem. */
    static BatchCreateExpenses.Candidate toCandidate(BatchExpenseItem item) {
        Money money;
        try {
            money = new Money(
                    new BigDecimal(item.getMoney().getAmount()),
                    Currency.getInstance(item.getMoney().getCurrency()));
        } catch (IllegalArgumentException e) {
            return new BatchCreateExpenses.Candidate.Unparseable("money", safeMessage(e));
        }
        return new BatchCreateExpenses.Candidate.Ready(new NewExpense(
                money, toDomain(item.getCategory()), item.getIncurredOn(), item.getMerchant(), item.getNote()));
    }

    static BatchResponse toBatchResponse(BatchCreateExpenses.Result result) {
        List<BatchItemResult> items = result.results().stream()
                .map(r -> {
                    var out = new BatchItemResult(
                            r.index(), r.id() != null ? BatchItemStatus.CREATED : BatchItemStatus.REJECTED);
                    if (r.id() != null) {
                        out.id(r.id());
                    }
                    if (r.problem() != null) {
                        out.problem(
                                new ItemProblem(r.problem().code(), r.problem().message())
                                        .field(r.problem().field()));
                    }
                    return out;
                })
                .toList();
        return new BatchResponse((int) result.created(), (int) result.rejected(), items);
    }

    private static String safeMessage(IllegalArgumentException e) {
        return e instanceof NumberFormatException ? "Not a valid decimal number" : String.valueOf(e.getMessage());
    }
}
