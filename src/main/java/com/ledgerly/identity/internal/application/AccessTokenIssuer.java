package com.ledgerly.identity.internal.application;

import com.ledgerly.identity.internal.OwnTokenIssuing;
import com.ledgerly.identity.internal.infrastructure.AuthProperties;
import com.ledgerly.identity.internal.infrastructure.JwtKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Signs access tokens. The claims are only {@code sub}, {@code iat}, {@code exp} and {@code jti}: no email and
 * no roles, because roles are per team and are read from the database on every call.
 */
@OwnTokenIssuing
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtKeys keys;
    private final AuthProperties props;
    private final Clock clock;

    AccessTokenIssuer(JwtEncoder encoder, JwtKeys keys, AuthProperties props, Clock clock) {
        this.encoder = encoder;
        this.keys = keys;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Signs an ES256 access token for the user.
     *
     * @return the compact JWT string
     */
    public String issue(UUID userId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header =
                JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.keyId()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** How long an access token lives, in seconds (the {@code expiresIn} of a login). */
    public long ttlSeconds() {
        return props.accessTokenTtl().toSeconds();
    }
}
