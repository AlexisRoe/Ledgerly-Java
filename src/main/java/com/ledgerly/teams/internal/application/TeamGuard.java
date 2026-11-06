package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.error.Forbidden;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.internal.domain.Membership;
import com.ledgerly.teams.internal.domain.MembershipId;
import com.ledgerly.teams.internal.infrastructure.MembershipRepository;
import com.ledgerly.teams.internal.infrastructure.TeamRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Checks shared by the use cases that change who belongs to a team. {@link #lockAndRequireOwner} also takes
 * the team row lock, which is what makes the last-OWNER rule hold under concurrency.
 */
@Component
class TeamGuard {

    private final TeamRepository teams;
    private final MembershipRepository memberships;

    TeamGuard(TeamRepository teams, MembershipRepository memberships) {
        this.teams = teams;
        this.memberships = memberships;
    }

    /** Not a teammate: 404, so the team's existence is not revealed. */
    Membership requireMember(UUID teamId, UUID userId) {
        return memberships.findById(new MembershipId(teamId, userId)).orElseThrow(NotFound::new);
    }

    /**
     * Locks the team row, then checks the caller is (still) an OWNER. The check must come after the lock: a
     * caller demoted by a concurrent request has to see that demotion.
     */
    void lockAndRequireOwner(UUID teamId, UUID caller) {
        teams.lockById(teamId).orElseThrow(NotFound::new);
        Membership membership = requireMember(teamId, caller);
        if (membership.role() != Role.OWNER) {
            throw new Forbidden("Only an owner may do this");
        }
    }

    /** Locks the team row for a caller who may be of any role (leaving). */
    void lock(UUID teamId) {
        teams.lockById(teamId).orElseThrow(NotFound::new);
    }

    boolean isLastOwner(UUID teamId) {
        return memberships.countByIdTeamIdAndRole(teamId, Role.OWNER) <= 1;
    }
}
