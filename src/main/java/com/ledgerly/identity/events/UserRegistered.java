package com.ledgerly.identity.events;

import com.ledgerly.shared.id.UserId;
import java.time.Instant;

public record UserRegistered(UserId userId, Instant occurredAt) {}
