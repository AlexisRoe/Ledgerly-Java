package com.ledgerly.shared.money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount together with its currency. Always positive, and always carrying exactly the currency's
 * minor-unit scale, so that {@code equals} and {@code hashCode} behave like value equality.
 *
 * <p>Pseudo currencies (XAU, XTS, XXX, ...) report -1 fraction digits and are rejected.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException("Not a monetary currency: " + currency);
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        if (amount.stripTrailingZeros().scale() > digits) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " has more than " + digits + " decimal places for " + currency);
        }
        amount = amount.setScale(digits);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatch(currency, other.currency);
        }
    }
}
