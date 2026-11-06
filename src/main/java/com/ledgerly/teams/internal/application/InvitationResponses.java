package com.ledgerly.teams.internal.application;

import com.ledgerly.identity.IdentityFacade;
import com.ledgerly.identity.UserSummary;
import com.ledgerly.shared.error.Conflict;
import com.ledgerly.shared.error.ErrorCatalogue;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.teams.internal.domain.Invitation;
import com.ledgerly.teams.internal.domain.InvitationStatus;
import com.ledgerly.teams.internal.infrastructure.InvitationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The checks accepting and declining have in common. */
@Component
class InvitationResponses {

    private final InvitationRepository invitations;
    private final IdentityFacade identity;

    InvitationResponses(InvitationRepository invitations, IdentityFacade identity) {
        this.invitations = invitations;
        this.identity = identity;
    }

    /**
     * The invitation, if it exists and was sent to the caller's email. Anyone else gets 404, so an invitation
     * id guessed or leaked to the wrong person reveals nothing.
     */
    Addressed load(UUID invitationId, UserId caller) {
        Invitation invitation = invitations.findById(invitationId).orElseThrow(NotFound::new);
        UserSummary user = identity.find(caller).orElseThrow(NotFound::new);
        if (!Invitation.normalise(user.email()).equals(invitation.email())) {
            throw new NotFound();
        }
        return new Addressed(invitation, user);
    }

    void requirePending(Invitation invitation, Instant now) {
        InvitationStatus status = invitation.status(now);
        if (status == InvitationStatus.EXPIRED) {
            throw new Conflict(ErrorCatalogue.INVITATION_EXPIRED, "This invitation has expired");
        }
        if (status != InvitationStatus.PENDING) {
            throw new Conflict(ErrorCatalogue.INVITATION_NOT_PENDING, "This invitation is " + status);
        }
    }

    record Addressed(Invitation invitation, UserSummary user) {}
}
