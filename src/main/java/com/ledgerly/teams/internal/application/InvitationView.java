package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.internal.domain.Invitation;
import com.ledgerly.teams.internal.domain.InvitationStatus;
import java.time.Instant;
import java.util.UUID;

public record InvitationView(UUID id, Role role, InvitationStatus status, Instant expiresAt) {

    static InvitationView of(Invitation invitation, Instant now) {
        return new InvitationView(invitation.id(), invitation.role(), invitation.status(now), invitation.expiresAt());
    }
}
