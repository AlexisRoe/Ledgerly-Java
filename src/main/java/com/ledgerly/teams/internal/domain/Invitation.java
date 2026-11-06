package com.ledgerly.teams.internal.domain;

import com.ledgerly.shared.role.Role;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** An owner's offer to an email address to join a team with a role. */
@Entity
@Table(name = "invitations")
public class Invitation {

    @Id
    private UUID id;

    private UUID teamId;
    private String email;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Enumerated(EnumType.STRING)
    private InvitationStatus status;

    private UUID invitedBy;
    private Instant createdAt;
    private Instant expiresAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Invitation() {}

    /** A new PENDING invitation for {@code email} (stored normalised) that expires {@code ttl} after {@code now}. */
    public Invitation(UUID teamId, String email, Role role, UUID invitedBy, Instant now, Duration ttl) {
        this.id = com.ledgerly.shared.id.UuidV7.generate();
        this.teamId = teamId;
        this.email = normalise(email);
        this.role = role;
        this.status = InvitationStatus.PENDING;
        this.invitedBy = invitedBy;
        this.createdAt = now;
        this.expiresAt = now.plus(ttl);
    }

    /** Trims and lower-cases an address so it can be compared with an account's email. */
    public static String normalise(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /** Expiry is computed from the clock, so no scheduler has to flip a flag. */
    public InvitationStatus status(Instant now) {
        if (status == InvitationStatus.PENDING && !expiresAt.isAfter(now)) {
            return InvitationStatus.EXPIRED;
        }
        return status;
    }

    /** PENDING -> ACCEPTED. The caller has already checked the state. */
    public void accept() {
        status = InvitationStatus.ACCEPTED;
    }

    /** PENDING -> DECLINED. */
    public void decline() {
        status = InvitationStatus.DECLINED;
    }

    /** PENDING -> REVOKED. */
    public void revoke() {
        status = InvitationStatus.REVOKED;
    }

    /** The invitation's id (UUIDv7). */
    public UUID id() {
        return id;
    }

    /** The team the invitation is for. */
    public UUID teamId() {
        return teamId;
    }

    /** The invited address, normalised. */
    public String email() {
        return email;
    }

    /** The role the invited person gets on accepting. */
    public Role role() {
        return role;
    }

    /** The moment after which it can no longer be answered. */
    public Instant expiresAt() {
        return expiresAt;
    }
}
