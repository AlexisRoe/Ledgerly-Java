package com.ledgerly.shared.id;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Embeddable (not a converted basic type) because Hibernate does not allow converters on identifiers. */
@Embeddable
public record ExpenseId(@Column(name = "id") UUID value) implements Serializable {

    public ExpenseId {
        Objects.requireNonNull(value, "value");
    }

    /** A new time-ordered id. */
    public static ExpenseId random() {
        return new ExpenseId(UuidV7.generate());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
