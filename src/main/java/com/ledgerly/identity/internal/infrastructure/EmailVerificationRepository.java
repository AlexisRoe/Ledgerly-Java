package com.ledgerly.identity.internal.infrastructure;

import com.ledgerly.identity.internal.domain.EmailVerification;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Email-verification tokens, found by the hash of the raw token. */
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {

    /** Locked so two requests with the same token cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findByTokenHash(String tokenHash);
}
