package com.ledgerly.identity.internal.application;

import com.ledgerly.identity.events.UserVerified;
import com.ledgerly.identity.internal.OwnTokenIssuing;
import com.ledgerly.identity.internal.infrastructure.EmailVerificationRepository;
import com.ledgerly.identity.internal.infrastructure.UserRepository;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.id.UserId;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@OwnTokenIssuing
@Service
public class VerifyEmail {

    private final EmailVerificationRepository verifications;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    VerifyEmail(
            EmailVerificationRepository verifications,
            UserRepository users,
            ApplicationEventPublisher events,
            Clock clock) {
        this.verifications = verifications;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void execute(String token) {
        Instant now = clock.instant();
        var verification = verifications
                .findByTokenHash(OpaqueTokens.hash(token))
                .filter(v -> v.isUsable(now))
                .orElseThrow(() -> new InvalidRequest("token", "Invalid or expired token"));
        verification.markUsed(now);
        var user = users.findById(verification.userId()).orElseThrow();
        user.markVerified();
        events.publishEvent(new UserVerified(new UserId(verification.userId()), now));
    }
}
