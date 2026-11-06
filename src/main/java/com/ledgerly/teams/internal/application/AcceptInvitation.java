package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.error.Conflict;
import com.ledgerly.shared.error.ErrorCatalogue;
import com.ledgerly.shared.error.Forbidden;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.events.TeamEvent;
import com.ledgerly.teams.internal.domain.Membership;
import com.ledgerly.teams.internal.domain.MembershipId;
import com.ledgerly.teams.internal.infrastructure.MembershipRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns an invitation addressed to the caller into a membership. */
@Service
public class AcceptInvitation {

    /** The team joined and the role received. */
    public record Result(UUID teamId, Role role) {}

    private final InvitationResponses responses;
    private final TeamGuard guard;
    private final MembershipRepository memberships;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AcceptInvitation(
            InvitationResponses responses,
            TeamGuard guard,
            MembershipRepository memberships,
            ApplicationEventPublisher events,
            Clock clock) {
        this.responses = responses;
        this.guard = guard;
        this.memberships = memberships;
        this.events = events;
        this.clock = clock;
    }

    /** Needs: addressed to you, you are Verified, not expired, still pending, and you are not a teammate yet. */
    @Transactional
    public Result execute(UUID invitationId, UserId caller) {
        var addressed = responses.load(invitationId, caller);
        var invitation = addressed.invitation();
        if (!addressed.user().verified()) {
            throw new Forbidden(ErrorCatalogue.EMAIL_NOT_VERIFIED, "Verify your email address before accepting");
        }
        Instant now = clock.instant();
        responses.requirePending(invitation, now);
        // Same team lock as every membership change, so accepting cannot interleave with removing.
        guard.lock(invitation.teamId());
        if (memberships.existsById(new MembershipId(invitation.teamId(), caller.value()))) {
            throw new Conflict(ErrorCatalogue.ALREADY_MEMBER, "You are already a teammate");
        }
        memberships.save(new Membership(invitation.teamId(), caller.value(), invitation.role(), now));
        invitation.accept();
        events.publishEvent(
                new TeamEvent.InvitationAccepted(new TeamId(invitation.teamId()), caller, invitationId, now));
        return new Result(invitation.teamId(), invitation.role());
    }
}
