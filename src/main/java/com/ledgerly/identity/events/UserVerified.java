package com.ledgerly.identity.events;

import com.ledgerly.shared.id.UserId;
import java.time.Instant;

public record UserVerified(UserId userId, Instant occurredAt) {}
