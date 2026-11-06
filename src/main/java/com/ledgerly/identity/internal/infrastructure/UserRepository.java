package com.ledgerly.identity.internal.infrastructure;

import com.ledgerly.identity.internal.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByExternalSubject(String externalSubject);
}
