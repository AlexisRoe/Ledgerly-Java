package com.ledgerly.identity.internal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A single-use verification token. Only its SHA-256 hash is stored. */
@Entity
@Table(name = "email_verifications")
public class EmailVerification {

    @Id
    private UUID id;

    private UUID userId;
    private String tokenHash;
    private Instant expiresAt;
    private @Nullable Instant usedAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected EmailVerification() {}

    public EmailVerification(UUID userId, String tokenHash, Instant expiresAt) {
        this.id = com.ledgerly.shared.id.UuidV7.generate();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        usedAt = now;
    }

    public UUID userId() {
        return userId;
    }
}
