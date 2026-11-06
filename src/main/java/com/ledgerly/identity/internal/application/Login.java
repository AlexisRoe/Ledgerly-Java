package com.ledgerly.identity.internal.application;

import com.ledgerly.identity.internal.domain.RefreshToken;
import com.ledgerly.identity.internal.domain.User;
import com.ledgerly.identity.internal.infrastructure.AuthProperties;
import com.ledgerly.identity.internal.infrastructure.RefreshTokenRepository;
import com.ledgerly.identity.internal.infrastructure.UserRepository;
import com.ledgerly.shared.error.Unauthenticated;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Login {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final AccessTokenIssuer accessTokens;
    private final AuthProperties props;
    private final Clock clock;
    private final String dummyHash;

    Login(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder encoder,
            AccessTokenIssuer accessTokens,
            AuthProperties props,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.accessTokens = accessTokens;
        this.props = props;
        this.clock = clock;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /**
     * Unknown email, wrong password and a locked account are all the same {@link Unauthenticated}. The failure
     * counter must be saved even though we throw, hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = Unauthenticated.class)
    public Session execute(String email, String rawPassword) {
        Instant now = clock.instant();
        Optional<User> found = users.findByEmail(User.normalise(email));
        if (found.isEmpty()) {
            encoder.matches(rawPassword, dummyHash); // spend the same time as for a real account
            throw new Unauthenticated();
        }
        User user = found.get();
        if (user.isLocked(now)) {
            throw new Unauthenticated();
        }
        if (!encoder.matches(rawPassword, user.passwordHash())) {
            user.recordFailedLogin(now, props.maxFailedLogins(), props.lockDuration());
            throw new Unauthenticated();
        }
        user.recordSuccessfulLogin();
        String refresh = OpaqueTokens.generate();
        refreshTokens.save(new RefreshToken(
                UUID.randomUUID(),
                user.id().value(),
                OpaqueTokens.hash(refresh),
                now,
                now.plus(props.refreshTokenTtl())));
        return new Session(
                accessTokens.issue(user.id().value()), accessTokens.ttlSeconds(), refresh, props.refreshTokenTtl());
    }
}
