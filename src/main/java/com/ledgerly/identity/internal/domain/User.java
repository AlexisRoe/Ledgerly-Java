package com.ledgerly.identity.internal.domain;

import com.ledgerly.shared.id.UserId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An account. Either has a password hash (own login) or an external subject (identity provider), see {@link #external}.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    private String email;
    private @Nullable String passwordHash;
    private @Nullable String externalSubject;
    private boolean verified;
    private Instant createdAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected User() {}

    private User(UUID id, String email, @Nullable String passwordHash, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    /** A new unverified account with a password hash; the email is stored normalised. */
    public static User register(String email, String passwordHash, Instant now) {
        return new User(com.ledgerly.shared.id.UuidV7.generate(), normalise(email), passwordHash, now);
    }

    /**
     * An account whose password and email verification are handled by an external identity provider. It has no
     * password here; {@code subject} is the provider's stable id for the person.
     */
    public static User external(String subject, String email, boolean verified, Instant now) {
        User user = new User(com.ledgerly.shared.id.UuidV7.generate(), normalise(email), null, now);
        user.externalSubject = subject;
        user.verified = verified;
        return user;
    }

    /** Emails are compared lower-cased and trimmed, so "Ann@Example.com" and "ann@example.com" are one account. */
    public static String normalise(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /** Links an existing account to the identity provider's id for the same (verified) email address. */
    public void linkExternal(String subject) {
        this.externalSubject = subject;
    }

    /** Records that the email address was confirmed. */
    public void markVerified() {
        verified = true;
    }

    /** Ledgerly's own id, the one every other module refers to. */
    public UserId id() {
        return new UserId(id);
    }

    /** The normalised email address. */
    public String email() {
        return email;
    }

    /** Empty for accounts managed by an external identity provider. */
    public @Nullable String passwordHash() {
        return passwordHash;
    }

    /** Whether the email address was confirmed (by us or by the identity provider). */
    public boolean verified() {
        return verified;
    }

    /** The identity provider's id for this person; null for accounts with a local password. */
    public @Nullable String externalSubject() {
        return externalSubject;
    }
}
