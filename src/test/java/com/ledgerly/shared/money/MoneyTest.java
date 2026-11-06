package com.ledgerly.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new Money(null, Currency.getInstance("EUR"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
    void rejectsZeroAndNegativeAmounts(String amount) {
        assertThatThrownBy(() -> Money.of(amount, "EUR")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsScaleAboveMinorUnits() {
        assertThatThrownBy(() -> Money.of("10.001", "EUR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.5", "JPY")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsScaleUpToMinorUnits() {
        assertThat(Money.of("1.001", "KWD").amount()).isEqualByComparingTo("1.001");
        assertThat(Money.of("1500", "JPY").amount()).isEqualByComparingTo("1500");
    }

    @Test
    void trailingZerosBeyondMinorUnitsAreHarmless() {
        assertThat(Money.of("10.000", "EUR")).isEqualTo(Money.of("10.00", "EUR"));
    }

    @Test
    void normalisesScale() {
        assertThat(Money.of("10", "EUR").amount().scale()).isEqualTo(2);
        assertThat(Money.of("10.5", "EUR").amount()).hasToString("10.50");
        assertThat(Money.of("1E+2", "EUR").amount()).hasToString("100.00");
        assertThat(Money.of("10", "JPY").amount().scale()).isZero();
    }

    @Test
    void equalsAndHashCodeIgnoreInputScale() {
        var a = Money.of("10.0", "EUR");
        var b = Money.of("10.00", "EUR");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void differentCurrenciesAreNotEqual() {
        assertThat(Money.of("10", "EUR")).isNotEqualTo(Money.of("10", "USD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"XAU", "XTS", "XXX"})
    void rejectsPseudoCurrencies(String code) {
        assertThatThrownBy(() -> Money.of("1", code)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plusAddsSameCurrency() {
        assertThat(Money.of("10.10", "EUR").plus(Money.of("0.95", "EUR"))).isEqualTo(Money.of("11.05", "EUR"));
    }

    @Test
    void plusAcrossCurrenciesThrows() {
        assertThatThrownBy(() -> Money.of("1", "EUR").plus(Money.of("1", "USD")))
                .isInstanceOf(CurrencyMismatch.class);
    }

    @Test
    void compareToOrdersSameCurrency() {
        assertThat(Money.of("1", "EUR")).isLessThan(Money.of("2", "EUR"));
    }

    @Test
    void compareToAcrossCurrenciesThrows() {
        assertThatThrownBy(() -> Money.of("1", "EUR").compareTo(Money.of("1", "USD")))
                .isInstanceOf(CurrencyMismatch.class);
    }
}
