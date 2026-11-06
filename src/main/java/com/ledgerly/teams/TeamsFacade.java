package com.ledgerly.teams;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import java.util.Optional;

/** The teams module's public API for other modules. */
public interface TeamsFacade {

    /** The user's role in the team, empty when they are not a teammate. */
    Optional<Role> roleOf(TeamId teamId, UserId userId);
}
