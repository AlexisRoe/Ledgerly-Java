package com.ledgerly.identity.internal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One link in a session's chain of refresh tokens. Every refresh replaces the token with a new one in the same
 * family; presenting a replaced token again means it leaked, and the whole family is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;

    private UUID familyId;
    private UUID userId;
    private String tokenHash;
    private Instant createdAt;
    private Instant expiresAt;
    private @Nullable Instant revokedAt;
    private @Nullable UUID replacedBy;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected RefreshToken() {}

    public RefreshToken(UUID familyId, UUID userId, String tokenHash, Instant now, Instant expiresAt) {
        this.id = com.ledgerly.shared.id.UuidV7.generate();
        this.familyId = familyId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** True when this token was already exchanged for a newer one. */
    public boolean isReplaced() {
        return replacedBy != null;
    }

    public void replaceWith(RefreshToken next) {
        this.replacedBy = next.id;
    }

    public UUID id() {
        return id;
    }

    public UUID familyId() {
        return familyId;
    }

    public UUID userId() {
        return userId;
    }
}
