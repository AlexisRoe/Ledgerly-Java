package com.ledgerly.shared.id;

import java.util.Objects;
import java.util.UUID;

public record ExpenseId(UUID value) {

    public ExpenseId {
        Objects.requireNonNull(value, "value");
    }

    public static ExpenseId random() {
        return new ExpenseId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
