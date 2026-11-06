package com.ledgerly.teams.internal.application;

import com.ledgerly.identity.IdentityFacade;
import com.ledgerly.identity.UserSummary;
import com.ledgerly.shared.error.Unauthenticated;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.internal.domain.Invitation;
import com.ledgerly.teams.internal.domain.InvitationStatus;
import com.ledgerly.teams.internal.domain.Team;
import com.ledgerly.teams.internal.infrastructure.InvitationRepository;
import com.ledgerly.teams.internal.infrastructure.MembershipRepository;
import com.ledgerly.teams.internal.infrastructure.TeamRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Served by teams, not identity, so identity never has to depend on teams (decision D5). */
@Service
public class GetMe {

    public record TeamRole(UUID teamId, String name, Role role) {}

    public record PendingInvitation(UUID id, UUID teamId, String teamName, Role role, Instant expiresAt) {}

    public record Me(
            UUID id, String email, boolean verified, List<TeamRole> teams, List<PendingInvitation> invitations) {}

    private final IdentityFacade identity;
    private final MembershipRepository memberships;
    private final TeamRepository teams;
    private final InvitationRepository invitations;
    private final Clock clock;

    GetMe(
            IdentityFacade identity,
            MembershipRepository memberships,
            TeamRepository teams,
            InvitationRepository invitations,
            Clock clock) {
        this.identity = identity;
        this.memberships = memberships;
        this.teams = teams;
        this.invitations = invitations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Me execute(UserId caller) {
        UserSummary user = identity.find(caller).orElseThrow(Unauthenticated::new);
        var myMemberships = memberships.findByIdUserId(caller.value());
        Map<UUID, Team> teamsById =
                teams
                        .findAllById(
                                myMemberships.stream().map(m -> m.id().teamId()).toList())
                        .stream()
                        .collect(Collectors.toMap(Team::id, Function.identity()));
        List<TeamRole> roles = myMemberships.stream()
                .flatMap(
                        m -> Optional.ofNullable(teamsById.get(m.id().teamId()))
                                .map(t -> new TeamRole(t.id(), t.name(), m.role()))
                                .stream())
                .toList();

        // Invitations are matched by email, so only an address the user has proven to own may see them.
        List<PendingInvitation> pending = List.of();
        if (user.verified()) {
            Instant now = clock.instant();
            List<Invitation> open =
                    invitations.findByEmailAndStatus(Invitation.normalise(user.email()), InvitationStatus.PENDING);
            Map<UUID, Team> inviting = teams
                    .findAllById(open.stream().map(Invitation::teamId).toList())
                    .stream()
                    .collect(Collectors.toMap(Team::id, Function.identity()));
            pending = open.stream()
                    .filter(i -> i.status(now) == InvitationStatus.PENDING)
                    .flatMap(i -> Optional.ofNullable(inviting.get(i.teamId()))
                            .map(t -> new PendingInvitation(i.id(), t.id(), t.name(), i.role(), i.expiresAt()))
                            .stream())
                    .toList();
        }
        return new Me(user.id().value(), user.email(), user.verified(), roles, pending);
    }
}
