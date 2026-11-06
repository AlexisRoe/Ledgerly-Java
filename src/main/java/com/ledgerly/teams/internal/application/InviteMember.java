package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.events.TeamEvent;
import com.ledgerly.teams.internal.domain.Invitation;
import com.ledgerly.teams.internal.domain.InvitationStatus;
import com.ledgerly.teams.internal.infrastructure.InvitationRepository;
import com.ledgerly.teams.internal.infrastructure.TeamsProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lets an OWNER invite an email address to the team with a role. */
@Service
public class InviteMember {

    private final TeamGuard guard;
    private final InvitationRepository invitations;
    private final ApplicationEventPublisher events;
    private final TeamsProperties props;
    private final Clock clock;

    InviteMember(
            TeamGuard guard,
            InvitationRepository invitations,
            ApplicationEventPublisher events,
            TeamsProperties props,
            Clock clock) {
        this.guard = guard;
        this.invitations = invitations;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Never looks at whether the address belongs to a registered user or is already a teammate, so the
     * response cannot be used to find out. Re-inviting replaces the address's pending invitation.
     */
    @Transactional
    public InvitationView execute(UUID teamId, UserId caller, String email, Role role) {
        guard.lockAndRequireOwner(teamId, caller.value());
        Instant now = clock.instant();
        String normalised = Invitation.normalise(email);
        invitations
                .findByTeamIdAndEmailAndStatus(teamId, normalised, InvitationStatus.PENDING)
                .ifPresent(old -> {
                    old.revoke();
                    // Written before the insert below, or the one-pending-per-address index would reject it.
                    invitations.saveAndFlush(old);
                });
        Invitation invitation =
                invitations.save(new Invitation(teamId, normalised, role, caller.value(), now, props.invitationTtl()));
        events.publishEvent(new TeamEvent.MemberInvited(new TeamId(teamId), caller, invitation.id(), role.name(), now));
        return InvitationView.of(invitation, now);
    }
}
