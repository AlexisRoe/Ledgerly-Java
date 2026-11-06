package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.error.Conflict;
import com.ledgerly.shared.error.ErrorCatalogue;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.events.TeamEvent;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lets an OWNER change another member's role; the last OWNER cannot be demoted. */
@Service
public class ChangeMemberRole {

    /** The member and the role they now hold. */
    public record Result(UUID userId, Role role) {}

    private final TeamGuard guard;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ChangeMemberRole(TeamGuard guard, ApplicationEventPublisher events, Clock clock) {
        this.guard = guard;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The last OWNER cannot be demoted. The team row is locked first, so two owners demoting each other at the
     * same moment run one after the other: the second one finds it is no longer an owner.
     */
    @Transactional
    public Result execute(UUID teamId, UserId caller, UUID target, Role newRole) {
        guard.lockAndRequireOwner(teamId, caller.value());
        var membership = guard.requireMember(teamId, target);
        Role oldRole = membership.role();
        if (oldRole == Role.OWNER && newRole != Role.OWNER && guard.isLastOwner(teamId)) {
            throw new Conflict(ErrorCatalogue.LAST_OWNER, "A team must keep at least one owner");
        }
        membership.changeRole(newRole);
        events.publishEvent(new TeamEvent.MemberRoleChanged(
                new TeamId(teamId), caller, new UserId(target), oldRole.name(), newRole.name(), clock.instant()));
        return new Result(target, newRole);
    }
}
