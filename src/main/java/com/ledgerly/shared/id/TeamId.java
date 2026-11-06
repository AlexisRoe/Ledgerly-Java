package com.ledgerly.shared.id;

import java.util.Objects;
import java.util.UUID;

public record TeamId(UUID value) {

    public TeamId {
        Objects.requireNonNull(value, "value");
    }

    public static TeamId random() {
        return new TeamId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
