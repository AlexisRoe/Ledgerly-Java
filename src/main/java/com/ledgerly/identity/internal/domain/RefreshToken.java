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

    /**
     * @param familyId shared by all tokens of one session
     * @param tokenHash SHA-256 of the raw token
     */
    public RefreshToken(UUID familyId, UUID userId, String tokenHash, Instant now, Instant expiresAt) {
        this.id = com.ledgerly.shared.id.UuidV7.generate();
        this.familyId = familyId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    /** True once {@code expiresAt} has passed. */
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** True after logout or after a reuse was detected. */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** True when this token was already exchanged for a newer one. */
    public boolean isReplaced() {
        return replacedBy != null;
    }

    /** Marks this token as exchanged for {@code next}. */
    public void replaceWith(RefreshToken next) {
        this.replacedBy = next.id;
    }

    /** The token record's id (not the secret). */
    public UUID id() {
        return id;
    }

    /** The session this token belongs to. */
    public UUID familyId() {
        return familyId;
    }

    /** The account the session belongs to. */
    public UUID userId() {
        return userId;
    }
}
