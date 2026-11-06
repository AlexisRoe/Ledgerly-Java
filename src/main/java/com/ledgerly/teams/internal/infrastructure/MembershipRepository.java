package com.ledgerly.teams.internal.infrastructure;

import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.internal.domain.Membership;
import com.ledgerly.teams.internal.domain.MembershipId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Memberships table: who belongs to which team, with their role. */
public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    /** How many members hold the role in the team (used for the last-OWNER rule). */
    long countByIdTeamIdAndRole(UUID teamId, Role role);

    /** All teams a user belongs to. */
    List<Membership> findByIdUserId(UUID userId);
}
