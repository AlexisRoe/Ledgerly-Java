package com.ledgerly.identity.internal.application;

import com.ledgerly.identity.events.UserRegistered;
import com.ledgerly.identity.internal.OwnTokenIssuing;
import com.ledgerly.identity.internal.domain.EmailVerification;
import com.ledgerly.identity.internal.domain.User;
import com.ledgerly.identity.internal.infrastructure.AuthProperties;
import com.ledgerly.identity.internal.infrastructure.EmailVerificationRepository;
import com.ledgerly.identity.internal.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@OwnTokenIssuing
@Service
public class Register {

    private final UserRepository users;
    private final EmailVerificationRepository verifications;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;
    private final AuthProperties props;
    private final Clock clock;

    Register(
            UserRepository users,
            EmailVerificationRepository verifications,
            PasswordEncoder encoder,
            ApplicationEventPublisher events,
            AuthProperties props,
            Clock clock) {
        this.users = users;
        this.verifications = verifications;
        this.encoder = encoder;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Same outcome for a new and an already registered email, so addresses cannot be enumerated. The password
     * is hashed in both cases so the two paths also take about the same time.
     */
    @Transactional
    public void execute(String email, String rawPassword) {
        String normalised = User.normalise(email);
        String hash = encoder.encode(rawPassword);
        if (users.findByEmail(normalised).isPresent()) {
            return;
        }
        Instant now = clock.instant();
        User user = User.register(normalised, hash, now);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            return; // lost a race with a concurrent registration of the same address
        }
        String token = OpaqueTokens.generate();
        verifications.save(
                new EmailVerification(user.id().value(), OpaqueTokens.hash(token), now.plus(props.verificationTtl())));
        events.publishEvent(new UserRegistered(user.id(), now));
        events.publishEvent(new VerificationLinkIssued(user.id().value(), token));
    }
}
