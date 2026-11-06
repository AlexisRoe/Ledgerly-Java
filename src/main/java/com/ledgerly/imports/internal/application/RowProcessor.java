package com.ledgerly.imports.internal.application;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.expenses.NewExpense;
import com.ledgerly.shared.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Turns one CSV line into a {@link NewExpense} using the same value types and expense rules as the API (Money,
 * Category, the expense field checks). Anything wrong becomes a {@link RowRejected} for that line only.
 */
@Component
public class RowProcessor implements ItemProcessor<RawRow, NewExpense> {

    static final int MIN_COLUMNS = 5;
    static final int MAX_COLUMNS = 6;

    private final ExpensesFacade expenses;

    RowProcessor(ExpensesFacade expenses) {
        this.expenses = expenses;
    }

    @Override
    public @Nullable NewExpense process(RawRow row) {
        if (row.isBlank()) {
            return null; // an empty line is not an error; it is simply not a row
        }
        int line = row.line();
        var f = row.fields();
        if (f.size() < MIN_COLUMNS || f.size() > MAX_COLUMNS) {
            throw new RowRejected(
                    line,
                    "COLUMN_COUNT",
                    "Expected " + MIN_COLUMNS + " or " + MAX_COLUMNS + " columns but found " + f.size());
        }
        LocalDate date;
        try {
            date = LocalDate.parse(f.get(0).strip());
        } catch (DateTimeParseException e) {
            throw new RowRejected(line, "DATE_INVALID", "date must look like 2026-11-05");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(f.get(3).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RowRejected(line, "CURRENCY_INVALID", "Unknown currency code");
        }
        Money money;
        try {
            money = new Money(new BigDecimal(f.get(2).strip()), currency);
        } catch (IllegalArgumentException e) {
            throw new RowRejected(
                    line,
                    "AMOUNT_INVALID",
                    e instanceof NumberFormatException ? "amount is not a number" : String.valueOf(e.getMessage()));
        }
        Category category;
        try {
            category = Category.valueOf(f.get(4).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RowRejected(
                    line, "CATEGORY_INVALID", "category must be one of TRAVEL, MEALS, OFFICE, SOFTWARE, OTHER");
        }
        String note = f.size() > 5 && !f.get(5).isBlank() ? f.get(5).strip() : null;
        NewExpense candidate = new NewExpense(money, category, date, f.get(1).strip(), note);
        expenses.validate(candidate).ifPresent(problem -> {
            throw new RowRejected(line, "VALIDATION_FAILED", problem.field() + ": " + problem.message());
        });
        return candidate;
    }
}
