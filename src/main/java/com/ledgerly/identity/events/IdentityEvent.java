package com.ledgerly.identity.events;

import com.ledgerly.shared.id.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything audit may learn about accounts. These events have no team: they are stored without one and never
 * appear in a team's audit trail. They carry ids only (ADR 0001): no email, no token.
 */
public sealed interface IdentityEvent permits UserRegistered, UserVerified, RefreshTokenReuseDetected {

    /** Stable across re-delivery, so a listener can recognise an event it has already handled. */
    UUID eventId();

    /** The user the event is about (they are also the one who acted). */
    UserId actorId();

    Instant occurredAt();
}
