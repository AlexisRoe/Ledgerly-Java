package com.ledgerly.expenses.internal.infrastructure;

import com.ledgerly.expenses.internal.application.MembershipLookup;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.TeamsFacade;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Reads the caller's role from the teams module on every call, so a removal or demotion bites immediately. */
@Component
class TeamsMembershipLookup implements MembershipLookup {

    private final TeamsFacade teams;

    TeamsMembershipLookup(TeamsFacade teams) {
        this.teams = teams;
    }

    @Override
    public Optional<Role> roleOf(TeamId teamId, UserId userId) {
        return teams.roleOf(teamId, userId);
    }
}
