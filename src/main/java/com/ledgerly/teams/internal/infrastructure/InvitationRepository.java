package com.ledgerly.teams.internal.infrastructure;

import com.ledgerly.teams.internal.domain.Invitation;
import com.ledgerly.teams.internal.domain.InvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Invitations table. Lookups by id are always combined with the team or the addressee. */
public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /** An invitation of that team, or empty (also when it belongs to another team). */
    Optional<Invitation> findByIdAndTeamId(UUID id, UUID teamId);

    /** The address's invitation in that status; at most one PENDING exists per address and team. */
    Optional<Invitation> findByTeamIdAndEmailAndStatus(UUID teamId, String email, InvitationStatus status);

    /** Everything sent to an (already normalised) address in that status. */
    List<Invitation> findByEmailAndStatus(String email, InvitationStatus status);
}
