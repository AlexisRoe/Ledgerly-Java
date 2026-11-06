package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.events.TeamEvent;
import com.ledgerly.teams.internal.domain.Membership;
import com.ledgerly.teams.internal.domain.Team;
import com.ledgerly.teams.internal.infrastructure.MembershipRepository;
import com.ledgerly.teams.internal.infrastructure.TeamRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a team and makes the caller its first OWNER, in one transaction. */
@Service
public class CreateTeam {

    /** The new team's id and name. */
    public record Result(UUID id, String name) {}

    private final TeamRepository teams;
    private final MembershipRepository memberships;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CreateTeam(TeamRepository teams, MembershipRepository memberships, ApplicationEventPublisher events, Clock clock) {
        this.teams = teams;
        this.memberships = memberships;
        this.events = events;
        this.clock = clock;
    }

    /** The creator becomes the team's first OWNER. */
    @Transactional
    public Result execute(String name, UserId creator) {
        Instant now = clock.instant();
        Team team = teams.save(new Team(name, now));
        memberships.save(new Membership(team.id(), creator.value(), Role.OWNER, now));
        events.publishEvent(new TeamEvent.TeamCreated(new TeamId(team.id()), creator, now));
        return new Result(team.id(), team.name());
    }
}
