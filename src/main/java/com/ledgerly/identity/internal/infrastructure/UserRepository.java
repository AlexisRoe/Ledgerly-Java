package com.ledgerly.identity.internal.infrastructure;

import com.ledgerly.identity.internal.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Accounts table. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Lookup by normalised email. */
    Optional<User> findByEmail(String email);

    /** Lookup by the identity provider's subject. */
    Optional<User> findByExternalSubject(String externalSubject);
}
