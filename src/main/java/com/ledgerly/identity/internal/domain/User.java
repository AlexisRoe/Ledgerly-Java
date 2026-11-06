package com.ledgerly.identity.internal.domain;

import com.ledgerly.shared.id.UserId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    private String email;
    private String passwordHash;
    private boolean verified;
    private Instant createdAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected User() {}

    private User(UUID id, String email, String passwordHash, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public static User register(String email, String passwordHash, Instant now) {
        return new User(com.ledgerly.shared.id.UuidV7.generate(), normalise(email), passwordHash, now);
    }

    /** Emails are compared lower-cased and trimmed, so "Ann@Example.com" and "ann@example.com" are one account. */
    public static String normalise(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public void markVerified() {
        verified = true;
    }

    public UserId id() {
        return new UserId(id);
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public boolean verified() {
        return verified;
    }
}
