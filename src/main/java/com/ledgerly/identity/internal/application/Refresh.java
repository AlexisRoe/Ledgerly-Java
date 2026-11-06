package com.ledgerly.identity.internal.application;

import com.ledgerly.identity.events.RefreshTokenReuseDetected;
import com.ledgerly.identity.internal.OwnTokenIssuing;
import com.ledgerly.identity.internal.domain.RefreshToken;
import com.ledgerly.identity.internal.infrastructure.AuthProperties;
import com.ledgerly.identity.internal.infrastructure.RefreshTokenRepository;
import com.ledgerly.shared.error.Unauthenticated;
import com.ledgerly.shared.id.UserId;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exchanges a refresh token for a new access token and a new refresh token (rotation). */
@OwnTokenIssuing
@Service
public class Refresh {

    private static final Logger log = LoggerFactory.getLogger(Refresh.class);

    private final RefreshTokenRepository tokens;
    private final AccessTokenIssuer accessTokens;
    private final ApplicationEventPublisher events;
    private final AuthProperties props;
    private final Clock clock;

    Refresh(
            RefreshTokenRepository tokens,
            AccessTokenIssuer accessTokens,
            ApplicationEventPublisher events,
            AuthProperties props,
            Clock clock) {
        this.tokens = tokens;
        this.accessTokens = accessTokens;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Rotates the refresh token. A token that was already exchanged is a stolen or replayed token, so the whole
     * family is revoked; that revocation must be committed although we then throw ({@code noRollbackFor}).
     */
    @Transactional(noRollbackFor = Unauthenticated.class)
    public Session execute(@Nullable String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new Unauthenticated();
        }
        Instant now = clock.instant();
        RefreshToken current =
                tokens.findByTokenHash(OpaqueTokens.hash(rawToken)).orElseThrow(Unauthenticated::new);
        if (current.isReplaced()) {
            tokens.revokeFamily(current.familyId(), now);
            events.publishEvent(new RefreshTokenReuseDetected(new UserId(current.userId()), current.familyId(), now));
            log.warn("Refresh token reuse detected; session family {} revoked", current.familyId());
            throw new Unauthenticated();
        }
        if (current.isRevoked() || current.isExpired(now)) {
            throw new Unauthenticated();
        }
        String next = OpaqueTokens.generate();
        RefreshToken successor = new RefreshToken(
                current.familyId(), current.userId(), OpaqueTokens.hash(next), now, now.plus(props.refreshTokenTtl()));
        tokens.save(successor);
        current.replaceWith(successor);
        return new Session(
                accessTokens.issue(current.userId()), accessTokens.ttlSeconds(), next, props.refreshTokenTtl());
    }
}
