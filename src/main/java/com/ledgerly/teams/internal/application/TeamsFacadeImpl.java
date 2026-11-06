package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.TeamsFacade;
import com.ledgerly.teams.internal.domain.MembershipId;
import com.ledgerly.teams.internal.infrastructure.MembershipRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TeamsFacadeImpl implements TeamsFacade {

    private final MembershipRepository memberships;

    TeamsFacadeImpl(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Role> roleOf(TeamId teamId, UserId userId) {
        return memberships
                .findById(new MembershipId(teamId.value(), userId.value()))
                .map(m -> m.role());
    }
}
