package com.ledgerly.identity.events;

import com.ledgerly.shared.id.UserId;
import java.time.Instant;
import java.util.UUID;

/** A rotated refresh token was presented again: the whole session family was revoked. */
public record RefreshTokenReuseDetected(UserId userId, UUID familyId, Instant occurredAt) {}
