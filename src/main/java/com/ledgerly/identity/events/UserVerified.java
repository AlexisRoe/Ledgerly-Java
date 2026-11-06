package com.ledgerly.identity.events;

import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.id.UuidV7;
import java.time.Instant;
import java.util.UUID;

/** The account's email address was confirmed. */
public record UserVerified(UUID eventId, UserId actorId, Instant occurredAt) implements IdentityEvent {

    /** Fills in a fresh event id. */
    public UserVerified(UserId actorId, Instant occurredAt) {
        this(UuidV7.generate(), actorId, occurredAt);
    }
}
