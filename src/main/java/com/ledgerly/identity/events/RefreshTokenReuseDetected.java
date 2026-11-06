package com.ledgerly.identity.events;

import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.id.UuidV7;
import java.time.Instant;
import java.util.UUID;

/** A rotated refresh token was presented again: the whole session family was revoked. */
public record RefreshTokenReuseDetected(UUID eventId, UserId actorId, UUID familyId, Instant occurredAt)
        implements IdentityEvent {

    /** @param familyId the session whose tokens were all revoked as a result */
    public RefreshTokenReuseDetected(UserId actorId, UUID familyId, Instant occurredAt) {
        this(UuidV7.generate(), actorId, familyId, occurredAt);
    }
}
