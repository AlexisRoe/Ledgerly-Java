package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.internal.domain.Invitation;
import com.ledgerly.teams.internal.domain.InvitationStatus;
import java.time.Instant;
import java.util.UUID;

/** An invitation as shown to the API. {@code status} may be EXPIRED, which is never stored. */
public record InvitationView(UUID id, Role role, InvitationStatus status, Instant expiresAt) {

    /** @param now the time against which expiry is judged */
    static InvitationView of(Invitation invitation, Instant now) {
        return new InvitationView(invitation.id(), invitation.role(), invitation.status(now), invitation.expiresAt());
    }
}
