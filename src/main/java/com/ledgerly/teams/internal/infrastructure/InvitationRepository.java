package com.ledgerly.teams.internal.infrastructure;

import com.ledgerly.teams.internal.domain.Invitation;
import com.ledgerly.teams.internal.domain.InvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByIdAndTeamId(UUID id, UUID teamId);

    Optional<Invitation> findByTeamIdAndEmailAndStatus(UUID teamId, String email, InvitationStatus status);

    List<Invitation> findByEmailAndStatus(String email, InvitationStatus status);
}
