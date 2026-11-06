package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.error.Conflict;
import com.ledgerly.shared.error.ErrorCatalogue;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.teams.events.TeamEvent;
import com.ledgerly.teams.internal.domain.InvitationStatus;
import com.ledgerly.teams.internal.infrastructure.InvitationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevokeInvitation {

    private final TeamGuard guard;
    private final InvitationRepository invitations;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    RevokeInvitation(TeamGuard guard, InvitationRepository invitations, ApplicationEventPublisher events, Clock clock) {
        this.guard = guard;
        this.invitations = invitations;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void execute(UUID teamId, UUID invitationId, UserId caller) {
        guard.lockAndRequireOwner(teamId, caller.value());
        Instant now = clock.instant();
        // Looked up by team too: another team's invitation id is simply not found.
        var invitation = invitations.findByIdAndTeamId(invitationId, teamId).orElseThrow(NotFound::new);
        if (invitation.status(now) != InvitationStatus.PENDING) {
            throw new Conflict(ErrorCatalogue.INVITATION_NOT_PENDING, "Only a pending invitation can be revoked");
        }
        invitation.revoke();
        events.publishEvent(new TeamEvent.InvitationRevoked(new TeamId(teamId), caller, invitationId, now));
    }
}
