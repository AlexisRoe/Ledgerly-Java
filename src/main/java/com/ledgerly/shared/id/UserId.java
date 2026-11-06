package com.ledgerly.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a user; a distinct type so it cannot be mixed up with a team id. */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "value");
    }

    /** A new time-ordered id. */
    public static UserId random() {
        return new UserId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
