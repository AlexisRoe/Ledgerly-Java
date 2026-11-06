package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.error.Forbidden;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.role.Role;
import com.ledgerly.shared.security.CurrentUser;
import com.ledgerly.teams.internal.domain.MembershipId;
import com.ledgerly.teams.internal.infrastructure.MembershipRepository;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Used from {@code @PreAuthorize("@teamAuthorization.anyOf(#teamId, 'OWNER')")}. Not a teammate gives 404 (the
 * team's existence is not revealed); a teammate with the wrong role gives 403. Roles are read from the database
 * on every call, so a demotion takes effect immediately.
 */
@Component("teamAuthorization")
public class TeamAuthorization {

    private final MembershipRepository memberships;
    private final CurrentUser currentUser;

    TeamAuthorization(MembershipRepository memberships, CurrentUser currentUser) {
        this.memberships = memberships;
        this.currentUser = currentUser;
    }

    /** The caller holds exactly one of the listed roles. */
    @Transactional(readOnly = true)
    public boolean anyOf(UUID teamId, String... roles) {
        EnumSet<Role> allowed = EnumSet.noneOf(Role.class);
        for (String role : roles) {
            allowed.add(Role.valueOf(role));
        }
        Role actual = roleOfCaller(teamId);
        if (!allowed.contains(actual)) {
            throw new Forbidden("Role " + actual + " may not do this");
        }
        return true;
    }

    /** The caller holds this role or a higher one on the ladder. */
    @Transactional(readOnly = true)
    public boolean atLeast(UUID teamId, String role) {
        Role actual = roleOfCaller(teamId);
        if (!actual.includes(Role.valueOf(role))) {
            throw new Forbidden("Role " + actual + " may not do this");
        }
        return true;
    }

    private Role roleOfCaller(UUID teamId) {
        UUID user = currentUser.id().value();
        return memberships
                .findById(new MembershipId(teamId, user))
                .orElseThrow(NotFound::new)
                .role();
    }
}
