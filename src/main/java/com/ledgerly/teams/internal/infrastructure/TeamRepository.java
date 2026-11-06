package com.ledgerly.teams.internal.infrastructure;

import com.ledgerly.teams.internal.domain.Team;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Teams table. {@code lockById} takes a row lock that serialises membership changes of one team. */
public interface TeamRepository extends JpaRepository<Team, UUID> {

    /**
     * {@code SELECT ... FOR UPDATE} on the team row. Everything that changes who owns a team takes this lock
     * first, so two such changes can never interleave and leave a team without an owner.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Team t where t.id = :id")
    Optional<Team> lockById(@Param("id") UUID id);
}
