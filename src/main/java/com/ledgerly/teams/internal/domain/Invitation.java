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

    public void accept() {
        status = InvitationStatus.ACCEPTED;
    }

    public void decline() {
        status = InvitationStatus.DECLINED;
    }

    public void revoke() {
        status = InvitationStatus.REVOKED;
    }

    public UUID id() {
        return id;
    }

    public UUID teamId() {
        return teamId;
    }

    public String email() {
        return email;
    }

    public Role role() {
        return role;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
