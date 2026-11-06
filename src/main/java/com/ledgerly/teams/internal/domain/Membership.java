package com.ledgerly.teams.internal.domain;

import com.ledgerly.shared.role.Role;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A user's belonging to a team, carrying exactly one role. */
@Entity
@Table(name = "memberships")
public class Membership {

    @EmbeddedId
    private MembershipId id;

    @Enumerated(EnumType.STRING)
    private Role role;

    private Instant joinedAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Membership() {}

    public Membership(UUID teamId, UUID userId, Role role, Instant now) {
        this.id = new MembershipId(teamId, userId);
        this.role = role;
        this.joinedAt = now;
    }

    public MembershipId id() {
        return id;
    }

    public UUID userId() {
        return id.userId();
    }

    public Role role() {
        return role;
    }

    public void changeRole(Role newRole) {
        this.role = newRole;
    }
}
