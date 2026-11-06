package com.ledgerly.expenses.internal.application;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import java.util.Optional;

/** Port: the caller's role in a team, empty when they are not a teammate. Adapter swapped in M6. */
public interface MembershipLookup {

    Optional<Role> roleOf(TeamId teamId, UserId userId);
}
