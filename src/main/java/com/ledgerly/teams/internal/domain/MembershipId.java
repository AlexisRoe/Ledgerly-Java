package com.ledgerly.teams.internal.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record MembershipId(UUID teamId, UUID userId) implements Serializable {}
