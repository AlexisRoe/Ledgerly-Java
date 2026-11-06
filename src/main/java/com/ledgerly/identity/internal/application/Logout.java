package com.ledgerly.identity.internal.application;

import com.ledgerly.identity.internal.OwnTokenIssuing;
import com.ledgerly.identity.internal.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@OwnTokenIssuing
@Service
public class Logout {

    private final RefreshTokenRepository tokens;
    private final Clock clock;

    Logout(RefreshTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Revokes the whole session family. Unknown or missing tokens are fine: logging out is idempotent. */
    @Transactional
    public void execute(@Nullable String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(OpaqueTokens.hash(rawToken))
                .ifPresent(t -> tokens.revokeFamily(t.familyId(), clock.instant()));
    }
}
