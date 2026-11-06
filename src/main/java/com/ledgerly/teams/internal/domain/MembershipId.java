package com.ledgerly.teams.internal.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite key: one membership per (team, user). */
@Embeddable
public record MembershipId(UUID teamId, UUID userId) implements Serializable {}
