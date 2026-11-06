package com.ledgerly.identity.internal.application;

import com.ledgerly.identity.internal.domain.RefreshToken;
import com.ledgerly.identity.internal.domain.User;
import com.ledgerly.identity.internal.infrastructure.AuthProperties;
import com.ledgerly.identity.internal.infrastructure.RefreshTokenRepository;
import com.ledgerly.identity.internal.infrastructure.UserRepository;
import com.ledgerly.shared.error.Unauthenticated;
import com.ledgerly.shared.ratelimit.RateLimit;
import com.ledgerly.shared.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Login {

    static final String LIMIT_SCOPE = "login";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final AccessTokenIssuer accessTokens;
    private final AuthProperties props;
    private final Clock clock;
    private final RateLimiter limiter;
    private final String dummyHash;

    Login(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder encoder,
            AccessTokenIssuer accessTokens,
            AuthProperties props,
            RateLimiter limiter,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.accessTokens = accessTokens;
        this.props = props;
        this.limiter = limiter;
        this.clock = clock;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /**
     * Unknown email, wrong password and a locked account are all the same {@link Unauthenticated}. After
     * {@code maxFailedLogins} failures for one email the login is refused, even with the right password, until the
     * lock period is over. The failures are counted per email address (whether or not it belongs to an account), by
     * a token bucket in memory; see {@link RateLimiter} for what that means with several instances.
     */
    @Transactional
    public Session execute(String email, String rawPassword) {
        Instant now = clock.instant();
        String normalised = User.normalise(email);
        RateLimit limit = failureLimit();
        if (limiter.isExhausted(LIMIT_SCOPE, normalised, limit)) {
            throw new Unauthenticated();
        }
        Optional<User> found = users.findByEmail(normalised);
        if (found.isEmpty()) {
            encoder.matches(rawPassword, dummyHash); // spend the same time as for a real account
            limiter.tryConsume(LIMIT_SCOPE, normalised, limit);
            throw new Unauthenticated();
        }
        User user = found.get();
        if (!encoder.matches(rawPassword, user.passwordHash())) {
            limiter.tryConsume(LIMIT_SCOPE, normalised, limit);
            throw new Unauthenticated();
        }
        limiter.reset(LIMIT_SCOPE, normalised, limit);
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

    private RateLimit failureLimit() {
        return RateLimit.intervally(props.maxFailedLogins(), props.lockDuration());
    }
}
