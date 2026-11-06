package com.ledgerly.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a team; a distinct type so it cannot be mixed up with a user id. */
public record TeamId(UUID value) {

    public TeamId {
        Objects.requireNonNull(value, "value");
    }

    /** A new time-ordered id. */
    public static TeamId random() {
        return new TeamId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
