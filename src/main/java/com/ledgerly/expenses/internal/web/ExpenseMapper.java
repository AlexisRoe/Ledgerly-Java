package com.ledgerly.expenses.internal.web;

import com.ledgerly.expenses.internal.application.ExpenseView;
import com.ledgerly.expenses.internal.application.ListExpenses;
import com.ledgerly.expenses.internal.domain.Category;
import com.ledgerly.expenses.internal.web.generated.model.ExpenseResponse;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.money.Money;
import java.math.BigDecimal;
import java.util.Currency;

/** DTO ↔ domain translation. Entities never reach this class; only views do. */
final class ExpenseMapper {

    private ExpenseMapper() {}

    static Money toMoney(com.ledgerly.expenses.internal.web.generated.model.Money dto, String field) {
        try {
            return new Money(new BigDecimal(dto.getAmount()), Currency.getInstance(dto.getCurrency()));
        } catch (IllegalArgumentException e) {
            // NumberFormatException is an IllegalArgumentException too.
            throw new InvalidRequest(field, safeMessage(e));
        }
    }

    static Category toDomain(com.ledgerly.expenses.internal.web.generated.model.Category dto) {
        return Category.valueOf(dto.name());
    }

    static ExpenseResponse toResponse(ExpenseView view) {
        var money = new com.ledgerly.expenses.internal.web.generated.model.Money(
                view.money().amount().toPlainString(), view.money().currency().getCurrencyCode());
        return new ExpenseResponse(
                        view.id().value(),
                        view.teamId().value(),
                        view.claimantId().value(),
                        money,
                        com.ledgerly.expenses.internal.web.generated.model.Category.valueOf(
                                view.category().name()),
                        view.incurredOn(),
                        view.merchant(),
                        com.ledgerly.expenses.internal.web.generated.model.ExpenseStatus.valueOf(
                                view.status().name()),
                        view.rejectionReasons())
                .note(view.note());
    }

    static com.ledgerly.expenses.internal.web.generated.model.ExpensePage toPage(ListExpenses.PageResult result) {
        var meta = new com.ledgerly.expenses.internal.web.generated.model.PageMetadata(
                result.size(), result.page(), result.totalElements(), result.totalPages());
        return new com.ledgerly.expenses.internal.web.generated.model.ExpensePage(
                result.items().stream().map(ExpenseMapper::toResponse).toList(), meta);
    }

    private static String safeMessage(IllegalArgumentException e) {
        return e instanceof NumberFormatException ? "Not a valid decimal number" : String.valueOf(e.getMessage());
    }
}
