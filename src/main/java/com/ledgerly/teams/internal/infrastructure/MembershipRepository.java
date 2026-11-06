package com.ledgerly.teams.internal.infrastructure;

import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.internal.domain.Membership;
import com.ledgerly.teams.internal.domain.MembershipId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    long countByIdTeamIdAndRole(UUID teamId, Role role);

    List<Membership> findByIdUserId(UUID userId);
}
