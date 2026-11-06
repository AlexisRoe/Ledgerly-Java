package com.ledgerly.identity.events;

import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.id.UuidV7;
import java.time.Instant;
import java.util.UUID;

/** A new account was created (not yet verified). */
public record UserRegistered(UUID eventId, UserId actorId, Instant occurredAt) implements IdentityEvent {

    /** Fills in a fresh event id. */
    public UserRegistered(UserId actorId, Instant occurredAt) {
        this(UuidV7.generate(), actorId, occurredAt);
    }
}
