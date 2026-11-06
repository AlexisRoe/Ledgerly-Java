package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.error.Conflict;
import com.ledgerly.shared.error.ErrorCatalogue;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.events.TeamEvent;
import com.ledgerly.teams.internal.infrastructure.MembershipRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemoveMember {

    private final TeamGuard guard;
    private final MembershipRepository memberships;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    RemoveMember(TeamGuard guard, MembershipRepository memberships, ApplicationEventPublisher events, Clock clock) {
        this.guard = guard;
        this.memberships = memberships;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void execute(UUID teamId, UserId caller, UUID target) {
        guard.lockAndRequireOwner(teamId, caller.value());
        var membership = guard.requireMember(teamId, target);
        if (membership.role() == Role.OWNER && guard.isLastOwner(teamId)) {
            throw new Conflict(ErrorCatalogue.LAST_OWNER, "A team must keep at least one owner");
        }
        memberships.delete(membership);
        events.publishEvent(
                new TeamEvent.MemberRemoved(new TeamId(teamId), caller, new UserId(target), clock.instant()));
    }
}
